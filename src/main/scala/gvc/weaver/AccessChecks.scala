package gvc.weaver
import gvc.transformer.IRGraph._
import viper.silicon.state.reconstructedPermissions.getPermissionsFor
import viper.silicon.state.CheckInfo
import viper.silver.ast.{Exp, FieldAccess, LocalVar, MethodCall}

import scala.collection.mutable

class AccessCheckException(message: String) extends Exception(message)

object AccessChecks {

  private object Names {
    val InstanceCounter = "_instance_counter"
    val DynamicOwnedFields = "dyn_fields"
    val StaticOwnedFields = "static_fields"
    val TempStaticOwnedFields = "temp_static_fields"
    val TempDynamicOwnedFields = "temp_dyn_fields"

    val ID = "_id"
  }

  private object Structs {
    def OwnedFields = new Struct("OwnedFields")
  }

  private object Vars {
    def InstanceCounter: Var = {
      new Var(new PointerType(IntType), Names.InstanceCounter)
    }
    def StaticOwnedFields: Var = {
      new Var(
        new ReferenceType(Structs.OwnedFields),
        name = Names.StaticOwnedFields
      )
    }
    def DynamicOwnedFields: Var = {
      new Var(
        new ArrayType(new ReferenceType(Structs.OwnedFields)),
        name = Names.DynamicOwnedFields
      )
    }
    def TempStaticOwnedFields: Var = {
      new Var(
        new ReferenceType(Structs.OwnedFields),
        name = Names.TempStaticOwnedFields
      )
    }
    def TempDynamicOwnedFields: Var = {
      new Var(
        new ArrayType(new ReferenceType(Structs.OwnedFields)),
        name = Names.TempDynamicOwnedFields
      )
    }
  }

  private object Methods {
    def InitOwnedFields: Method = {
      new Method(
        "initOwnedFields",
        Some(BoolType),
        None,
        None
      )
    }
    def AddFieldAccess: Method = {
      new Method(
        "addAccess",
        Some(BoolType),
        None,
        None
      )
    }
    def Assert: Method = {
      new Method(
        "assertAcc",
        Some(BoolType),
        None,
        None
      )
    }

    def AssertDisjoint: Method = {
      new Method(
        "assertDisjointAcc",
        Some(BoolType),
        None,
        None
      )
    }
    def AddStructAccess: Method = {
      new Method(
        "addStructAccess",
        Some(IntType),
        None,
        None
      )
    }
    def Join: Method = {
      new Method(
        "join",
        Some(BoolType),
        None,
        None
      )
    }
    def Disjoin: Method = {
      new Method(
        "disjoin",
        Some(BoolType),
        None,
        None
      )
    }
  }

  private object Commands {
    def InitCounter: Seq[Op] = {
      Seq(
        new AllocValue(IntType, Vars.InstanceCounter),
        new AssignMember(
          new DereferenceMember(Vars.InstanceCounter, IntType),
          new Int(0)
        )
      )
    }
    def InitStatic: Seq[Op] = {
      Seq(
        new AllocStruct(Structs.OwnedFields, Vars.StaticOwnedFields),
        new Invoke(
          Methods.InitOwnedFields,
          List(Vars.StaticOwnedFields, Vars.InstanceCounter),
          None
        )
      )
    }
    def InitDynamic: Seq[Op] = {
      Seq(
        new AllocArray(
          new ReferenceType(Structs.OwnedFields),
          new Int(1),
          Vars.DynamicOwnedFields
        ),
        new AllocStruct(
          Structs.OwnedFields,
          new ArrayMember(
            Vars.DynamicOwnedFields,
            Vars.DynamicOwnedFields.valueType,
            new Int(0)
          )
        ),
        new Invoke(
          Methods.InitOwnedFields,
          List(
            Commands.GetDynamicOwnedFields,
            Vars.InstanceCounter
          ),
          None
        )
      )
    }
    def GetDynamicOwnedFields: ArrayMember = {
      new ArrayMember(
        Vars.DynamicOwnedFields,
        new ReferenceType(Structs.OwnedFields),
        new Int(0)
      )
    }

    def Join(target: Expression, src: Expression): Invoke = {
      new Invoke(
        Methods.Join,
        List(
          target,
          src
        ),
        None
      )
    }
    def Disjoin(target: Expression, src: Expression): Invoke = new Invoke(
      Methods.Disjoin,
      List(
        target,
        src
      ),
      None
    )
    def StoreStatic: Assign = new Assign(
      Vars.TempStaticOwnedFields,
      Vars.StaticOwnedFields
    )
    def LoadStatic: Assign = new Assign(
      Vars.StaticOwnedFields,
      Vars.TempStaticOwnedFields
    )
    def StoreDynamic: Assign = new Assign(
      Vars.TempDynamicOwnedFields,
      Vars.DynamicOwnedFields
    )
  }

  private def isImprecise(method: Method): Boolean = {
    optionImprecise(method.precondition) || optionImprecise(
      method.postcondition
    )
  }

  private def optionImprecise(expr: Option[Expression]): Boolean = {
    expr match {
      case Some(_: Imprecise) => true
      case _                  => false
    }
  }

  class AccessTracker() {
    val callGraph = new CallGraph()
    var entry: Option[Method] = None
    val visited: mutable.Set[String] = mutable.Set[String]()
    val visitedStructs: mutable.Set[String] = mutable.Set[String]()
    val allocations: mutable.Set[(Method, Op)] = mutable.Set[(Method, Op)]()
    def requiresTracking: Boolean = visited.nonEmpty

    def requiresStaticSeparation: mutable.Set[String] = mutable.Set[String]()

    def visitedStruct(structName: String): Boolean = {
      visitedStructs.contains(structName)
    }

    def visitedMethod(method: Method): Boolean = {
      visited.contains(method.name)
    }
  }

  /* Tracks the callsites of each method, whether it calls an imprecise method, and where it returns.*/

  class Edge(
      var callsImprecise: Boolean,
      val callsites: mutable.Map[Op, InvokeInfo],
      val returns: mutable.ArrayBuffer[Op]
  )

  class InvokeInfo(
      val callingMethod: Method,
      val vprNode: MethodCall
  )

  class CallGraph extends Iterable[(Method, Edge)] {
    override def iterator: Iterator[(Method, Edge)] = graph.iterator
    private var graph: Map[Method, Edge] = Map[Method, Edge]()

    def getEdge(method: Method): Option[Edge] = {
      if (graph.contains(method)) {
        Some(graph(method))
      } else {
        None
      }
    }

    def addEdge(
        calledMethod: Method,
        callPosition: Op,
        vprNode: MethodCall,
        callingMethod: Method
    ): Unit = {
      val entry = (callPosition -> new InvokeInfo(
        callingMethod,
        vprNode
      ))
      if (graph isDefinedAt calledMethod) {
        graph(calledMethod).callsites += entry
      } else {
        val edge = new Edge(
          callsImprecise = false,
          mutable.Map[Op, InvokeInfo](entry),
          mutable.ArrayBuffer[Op]()
        )
        graph += calledMethod -> edge
      }
      if (isImprecise(calledMethod)) {
        if (!(graph isDefinedAt callingMethod)) {
          graph += callingMethod -> new Edge(
            callsImprecise = true,
            mutable.Map[Op, InvokeInfo](),
            mutable.ArrayBuffer[Op]()
          )
        } else {
          graph(callingMethod).callsImprecise = true
        }
      }
    }
    def addReturn(returnPosition: Op, returningMethod: Method): Unit = {
      if (graph isDefinedAt returningMethod) {
        graph(returningMethod).returns += returnPosition
      } else {
        graph += returningMethod -> new Edge(
          callsImprecise = false,
          mutable.Map[Op, InvokeInfo](),
          mutable.ArrayBuffer[Op](returnPosition)
        )
      }
    }
  }

  def injectSupport(ir: Program, tracker: AccessTracker): Unit = {
    /*  it's only necessary to add tracking if a field access check was inserted,
     *  at which point the method where the check occurs is marked as 'visited' by the
     *  AccessTracker.
     */
    if (tracker.requiresTracking) {
      ir.structs.foreach((st) => {
        st.addField(Names.ID, IntType)
      })
      for ((method: Method, edge: Edge) <- tracker.callGraph) {
        /* Modify the parameters of each method to accept OwnedFields objects as necessary */

        if (isImprecise(method) && edge.callsImprecise)
          injectParameters(method, edge)

        /* Pass the necessary OwnedFields objects when each method is called */
        for ((invocation, info) <- edge.callsites) {
          val invoke_op = invocation.asInstanceOf[Invoke]
          var afterwards = invocation
          while (
            afterwards.getNext.isDefined
            && afterwards.getNext.get.isInstanceOf[Invoke]
            && (afterwards.getNext.get
              .asInstanceOf[Invoke]
              .method
              .name == "assertAcc" || afterwards.getNext.get
              .asInstanceOf[Invoke]
              .method
              .name == "assertDisjointAcc")
          ) afterwards = afterwards.getNext.get

          if (isImprecise(method)) {

            if (!isImprecise(info.callingMethod)) {
              info.callingMethod.addExistingVar(Vars.DynamicOwnedFields)
              info.callingMethod.body.head
                .insertBefore(Commands.InitDynamic)

              if (tracker.requiresStaticSeparation.contains(method.name)) {
                info.callingMethod.addExistingVar(
                  Vars.TempStaticOwnedFields
                )
              }
              info.callingMethod.addExistingVar(Vars.StaticOwnedFields)
              info.callingMethod.addExistingVar(Vars.TempDynamicOwnedFields)
            }

            invoke_op.arguments = invoke_op.arguments ++ List(
              Vars.DynamicOwnedFields,
              Vars.StaticOwnedFields
            )

            if (optionImprecise(method.precondition)) {

              invocation.insertBefore(Commands.StoreStatic)
              invocation.insertBefore(
                Commands.Join(
                  Commands.GetDynamicOwnedFields,
                  Vars.StaticOwnedFields
                )
              )

              invocation.insertBefore(Commands.InitStatic)
              invocation.insertBefore(
                populateStatic(Vars.StaticOwnedFields, method.precondition)
              )

              invocation.insertBefore(
                Commands.Disjoin(
                  Commands.GetDynamicOwnedFields,
                  Vars.StaticOwnedFields
                )
              )

              if (optionImprecise(method.postcondition)) {

                afterwards.insertAfter(
                  Commands.Disjoin(
                    Commands.GetDynamicOwnedFields,
                    Vars.StaticOwnedFields
                  )
                )

                afterwards.insertAfter(Commands.LoadStatic)

                afterwards.insertAfter(
                  Commands.Join(
                    Commands.GetDynamicOwnedFields,
                    Vars.StaticOwnedFields
                  )
                )
                afterwards.insertAfter(
                  populateStatic(
                    Commands.GetDynamicOwnedFields,
                    method.postcondition
                  )
                )
              } else {

                afterwards.insertAfter(
                  Commands.Disjoin(
                    Commands.GetDynamicOwnedFields,
                    Vars.StaticOwnedFields
                  )
                )

                afterwards.insertAfter(Commands.LoadStatic)

                afterwards.insertAfter(
                  populateStatic(
                    Commands.GetDynamicOwnedFields,
                    method.postcondition
                  )
                )

                afterwards.insertAfter(Commands.InitDynamic)
              }
            } else {
              afterwards.insertAfter(Commands.StoreStatic)

              invocation.insertBefore(Commands.InitStatic)
              invocation.insertBefore(
                populateStatic(Vars.StaticOwnedFields, method.precondition)
              )

              invocation.insertBefore(Commands.StoreDynamic)
              invocation.insertBefore(Commands.InitDynamic)

              afterwards.insertAfter(
                Commands.Disjoin(
                  Vars.DynamicOwnedFields,
                  Vars.StaticOwnedFields
                )
              )
              afterwards.insertAfter(Commands.LoadStatic)

              afterwards.insertAfter(
                Commands.Join(
                  Vars.DynamicOwnedFields,
                  Vars.TempDynamicOwnedFields
                )
              )
              afterwards.insertAfter(
                populateStatic(
                  Vars.StaticOwnedFields,
                  method.precondition
                )
              )

              if (optionImprecise(method.postcondition)) {
                afterwards.insertAfter(
                  Commands.Join(
                    Vars.DynamicOwnedFields,
                    Vars.StaticOwnedFields
                  )
                )
                afterwards.insertAfter(
                  Commands.Join(
                    Vars.DynamicOwnedFields,
                    Vars.StaticOwnedFields
                  )
                )
              }
            }
          } else {
            //TODO: optimize so that _instance_counter is only passed to methods that eventually allocate memory
            invoke_op.arguments =
              invoke_op.arguments ++ List(Vars.InstanceCounter)
          }
        }
      }
      /* track each allocation, using _instance_counter or dynamic_fields as appropriate
       * to assign unique IDs to struct instances
       * */
      tracker.allocations.foreach(alloc => {
        val method = alloc._1
        val alloc_op = alloc._2
        alloc_op match {
          case structAlloc: AllocStruct =>
            /* if a struct allocation occurs in an imprecise context, it's unique identifier is provided
             * by the dynamic_fields object, and permissions to each of its fields are recorded.
             */
            val callsImprecise = tracker.callGraph.getEdge(method) match {
              case Some(value) => value.callsImprecise
              case None        => false
            }
            if (isImprecise(method) || callsImprecise) {
              injectIDField(structAlloc.struct, tracker)
              structAlloc.insertAfter(
                addDynamicStructAccess(
                  structAlloc.target,
                  structAlloc.struct
                )
              )
            } else {

              /* If it occurs in a fully-verified context, then the _instance_counter parameter is used to assign the ID
               * and is incremented. If this code is reached, we can assume that a field access runtime check must
               * occur elsewhere, so tracking allocations in fully-verified contexts is necessary. The following operations
               * occur in reverse order:
               */

              /* increment *(_instance_counter) */
              val deref_inst_counter =
                new DereferenceMember(Vars.InstanceCounter, IntType)
              structAlloc.insertAfter(
                new AssignMember(
                  deref_inst_counter,
                  new Binary(
                    BinaryOp.Add,
                    deref_inst_counter,
                    new Int(1)
                  )
                )
              )
              /* assign the new instance's _id field to the current value of *(_instance_counter) */
              structAlloc.insertAfter(
                new AssignMember(
                  new FieldMember(
                    structAlloc.target,
                    new StructField(structAlloc.struct, Names.ID, IntType)
                  ),
                  new DereferenceMember(Vars.InstanceCounter, IntType)
                )
              )
            }
        }
      })
      /*  initialize _instance_counter at the beginning of main. if a runtime check is required in main,
       *  instructions to create the necessary OwnedFields objects will have already been inserted, so it is safe
       *  to initialize _instance_counter as the final step here.
       */
      tracker.entry match {
        case Some(mainMethod) =>
          mainMethod.addExistingVar(Vars.InstanceCounter)
          mainMethod.body.head.insertBefore(Commands.InitCounter)
      }
    }
  }

  case class AccessPermission(
      root: Expression,
      struct: Struct,
      fieldName: String
  )

  def getAccessPermissionsAt(
      call: MethodCall,
      callingMethod: Method
  ): mutable.Set[AccessPermission] = {
    val accessSet = mutable.Set[AccessPermission]()
    getPermissionsFor(call).foreach {
      case fa: FieldAccess =>
        fa.rcv match {
          case LocalVar(name, _) =>
            val fieldName =
              fa.field.name.substring(fa.field.name.lastIndexOf('$') + 1)
            callingMethod.getVar(name) match {
              case Some(variable) =>
                val structType =
                  variable.valueType.asInstanceOf[ReferenceType].struct
                accessSet += AccessPermission(variable, structType, fieldName)
            }
        }
      case _ =>
        throw new AccessCheckException(
          "Only field permissions for structs that are LocalVars have been implemented."
        )
    }
    accessSet
  }
  def populateOwnedFields(
      info: InvokeInfo,
      spec: Option[Expression]
  ): Seq[Op] = {
    val call = info.vprNode
    val callingMethod = info.callingMethod

    val static: mutable.Set[AccessPermission] = getStaticComponent(spec)
    val available_permissions: mutable.Set[AccessPermission] =
      getAccessPermissionsAt(call, callingMethod)
    val dynamic = available_permissions.diff(static)
    val ops: mutable.ArrayBuffer[Op] = mutable.ArrayBuffer[Op]()
    dynamic.foreach(perm => {
      ops += addFieldAccess(Commands.GetDynamicOwnedFields, perm)
    })
    static.foreach(perm => {
      ops += addFieldAccess(Vars.StaticOwnedFields, perm)
    })
    ops.to[Seq]
  }

  def populateStatic(
      target: Expression,
      spec: Option[Expression]
  ): Seq[Op] = {
    val accessSet = getStaticComponent(spec)
    val ops: mutable.ArrayBuffer[Op] = mutable.ArrayBuffer[Op]()
    accessSet.foreach(perm => {
      ops += addFieldAccess(target, perm)
    })
    ops.to[Seq]
  }

  def getStaticComponent(
      expression: Option[Expression]
  ): mutable.Set[AccessPermission] = {
    val access = mutable.Set[AccessPermission]()
    expression match {
      case Some(expr) =>
        expr match {
          case imp: Imprecise =>
            access ++ getStaticComponent(imp.precise)
          case bin: Binary =>
            bin.right match {
              case acc: Accessibility =>
                access += toAccessPermission(acc)
                access ++ getStaticComponent(Some(bin.left))
              case _ => throw new WeaverException("Unknown conjunct type.")
            }
          case acc: Accessibility =>
            access += toAccessPermission(acc)
            access
          case _ => throw new WeaverException("Unknown conjunct type.")
        }
      case None => access
    }
  }

  def toAccessPermission(acc: Accessibility): AccessPermission = {
    acc.member match {
      case member: FieldMember => {
        AccessPermission(member.root, member.field.struct, member.field.name)
      }
      case _ =>
        throw new AccessCheckException("Unimplemented field access type.")
    }
  }

  def injectParameters(
      method: Method,
      edge: Edge
  ): Unit = {
    /* imprecise methods are passed two disjoint OwnedFields structs: one containing the fields that are statically
     * accessible at the method's callsite, and the other containing all other field access permissions that have been
     * recorded throughout runtime at every call to alloc.
     */
    if (isImprecise(method) && method.name != "main") {
      method.addParameter(
        Vars.DynamicOwnedFields.valueType,
        Names.DynamicOwnedFields
      )
      method.addParameter(
        Vars.StaticOwnedFields.valueType,
        Names.StaticOwnedFields
      )

      /* when an imprecise method returns, if its postcondition is precise, then only the statically verified fields of the
       * postcondition will be returned to the caller.
       */
      if (
        method.postcondition.isDefined && !optionImprecise(method.postcondition)
      ) {
        for (ret <- edge.returns) {
          ret.insertBefore(
            new AssignIndex(
              Vars.DynamicOwnedFields,
              new Int(0),
              Vars.StaticOwnedFields
            )
          )
        }
      }
    } else {
      /* fully verified methods are passed the _instance_counter to assign unique IDs to each struct allocation */
      if (method.name != "main")
        method.addParameter(new PointerType(IntType), Names.InstanceCounter)

    }
  }

  def getFieldIndex(structType: Struct, fieldName: String): Int = {
    val index =
      structType.fields.indexWhere(field => field.name.equals(fieldName))
    new Int(index)
  }

  def injectIDField(structType: Struct, tracker: AccessTracker): Unit = {
    if (!tracker.visitedStruct(structType.name)) {
      structType.addField(Names.ID, IntType)
      tracker.visitedStructs += structType.name
    }
  }

  def assertFieldAccess(
      check: CheckInfo,
      loc: FieldAccess,
      method: Method,
      tracker: AccessTracker
  ): Seq[Op] = {
    if (check.overlaps) tracker.requiresStaticSeparation += method.name
    tracker.visited += method.name
    val fieldName =
      loc.field.name.substring(loc.field.name.lastIndexOf('$') + 1)
    loc.rcv match {
      case LocalVar(name, _) =>
        method.getVar(name) match {
          case Some(variable) =>
            val structType =
              variable.valueType.asInstanceOf[ReferenceType].struct
            if (check.overlaps) {
              val check = new Invoke(
                Methods.AssertDisjoint,
                List(
                  Vars.StaticOwnedFields,
                  Commands.GetDynamicOwnedFields,
                  new FieldMember(variable, structType.fields.last),
                  getFieldIndex(structType, fieldName)
                ),
                None
              )
              Seq(check)
              Seq(
                new Invoke(
                  Methods.AssertDisjoint,
                  List(
                    Vars.StaticOwnedFields,
                    Commands.GetDynamicOwnedFields,
                    new FieldMember(variable, structType.fields.last),
                    getFieldIndex(structType, fieldName)
                  ),
                  None
                )
              )
            } else {
              Seq(
                new Invoke(
                  Methods.Assert,
                  List(
                    Commands.GetDynamicOwnedFields,
                    new FieldMember(variable, structType.fields.last),
                    getFieldIndex(structType, fieldName)
                  ),
                  None
                )
              )
            }
          case None =>
            throw new AccessCheckException(
              "No local variable exists for the given field permission."
            )
        }
      case _ =>
        throw new AccessCheckException(
          "Only field permissions for structs that are LocalVars have been implemented."
        )
    }
  }

  private def addFieldAccess(
      fields: Expression,
      perm: AccessPermission
  ): Invoke = {
    new Invoke(
      Methods.AddFieldAccess,
      List(
        fields,
        new FieldMember(perm.root, perm.struct.fields.last),
        new Int(perm.struct.fields.length - 1),
        getFieldIndex(perm.struct, perm.fieldName)
      ),
      None
    )
  }

  private def addStructAccess(
      fields: Expression,
      exprToDeref: Expression,
      structType: Struct
  ): Op = {
    new Invoke(
      Methods.AddStructAccess,
      List(fields, new Int(structType.fields.length - 1)),
      Some(
        new FieldMember(
          exprToDeref,
          new StructField(structType, Names.ID, IntType)
        )
      )
    )
  }

  def addDynamicStructAccess(
      exprToDeref: Expression,
      structType: Struct
  ): Op = {
    addStructAccess(
      Commands.GetDynamicOwnedFields,
      exprToDeref,
      structType
    )
  }

  def addStaticStructAccess(
      exprToDeref: Expression,
      structType: Struct
  ): Op = {
    addStructAccess(Vars.StaticOwnedFields, exprToDeref, structType)
  }
}
