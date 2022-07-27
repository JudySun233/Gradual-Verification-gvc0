package gvc.benchmarking

import doobie._
import doobie.implicits._
import cats.effect.IO
import doobie.util.transactor
import doobie._
import doobie.implicits._
import gvc.benchmarking.ExprType.ExprType
import gvc.benchmarking.SpecType.SpecType
import cats.effect.unsafe.implicits.global
import gvc.CC0Wrapper.Performance
import gvc.benchmarking.BenchmarkExecutor.ReservedProgram
import gvc.benchmarking.DAO.ErrorType.ErrorType
import gvc.benchmarking.Timing.TimedVerification

import scala.collection.mutable

class DBException(message: String) extends Exception(message)

object DAO {

  type DBConnection = Transactor.Aux[IO, Unit]

  object DynamicMeasurementMode {
    type DynamicMeasurementMode = String
    val Gradual = "gradual"
    val Framing = "framing"
    val Dynamic = "dynamic"
  }

  object ErrorType {
    type ErrorType = String
    val Compilation = "compilation"
    val Execution = "execution"
    val Verification = "verification"
    val Timeout = "timeout"
  }

  case class Hardware(id: Long, hardwareName: String, dateAdded: String)

  case class StoredProgram(id: Long,
                           hash: String,
                           dateAdded: String,
                           numLabels: Long)

  case class GlobalConfiguration(timeoutMinutes: Long, maxPaths: Long)

  case class Identity(vid: Long, hid: Long, nid: Option[Long])

  case class Version(id: Long, versionName: String, dateAdded: String)

  case class Permutation(id: Long,
                         programID: Long,
                         permutationHash: Array[Byte],
                         dateAdded: String)

  case class Step(pathID: Long, permutationID: Long, levelID: Long)

  case class CompletionMetadata(versionName: String,
                                srcFilename: String,
                                measurementMode: String,
                                totalCompleted: Long,
                                total: Long)

  case class Conjuncts(id: Long,
                       permutationID: Long,
                       versionID: Long,
                       total: Long,
                       eliminated: Long,
                       date: String)

  case class ProgramPath(id: Long, hash: String, programID: Long)

  case class StoredPerformance(id: Long,
                               programID: Long,
                               versionID: Long,
                               hardwareID: Long,
                               performanceDate: String,
                               modeMeasured: String,
                               stress: Int,
                               iter: Int,
                               ninetyFifth: BigDecimal,
                               fifth: BigDecimal,
                               median: BigDecimal,
                               mean: BigDecimal,
                               stdev: BigDecimal,
                               minimum: BigDecimal,
                               maximum: BigDecimal)

  private val DB_DRIVER = "com.mysql.cj.jdbc.Driver"

  def connect(credentials: BenchmarkDBCredentials): DBConnection = {
    val connection = Transactor.fromDriverManager[IO](
      DB_DRIVER,
      credentials.url, //"jdbc:mysql://localhost:3306/", // connect URL (driver-specific)
      credentials.username,
      credentials.password
    )
    Output.success(
      s"Connected to database as ${credentials.username}@${credentials.url}")
    connection
  }

  def resolveGlobalConfiguration(conn: DBConnection): GlobalConfiguration = {
    (sql"SELECT timeout_minutes, max_paths FROM global_configuration LIMIT 1"
      .query[GlobalConfiguration]
      .option
      .transact(conn)
      .unsafeRunSync()) match {
      case Some(value) => value
      case None =>
        throw new DBException(
          "Unable to resolve global database configuration.")
    }
  }

  def addOrResolveIdentity(config: ExecutorConfig,
                           xa: DBConnection): Identity = {
    val hid = addOrResolveHardware(config.hardware, xa)
    val vid = addOrResolveVersion(config.version, xa)
    val nid = addOrResolveNickname(config.nickname, xa)
    Identity(vid, hid, nid)
  }

  def addOrResolveNickname(
      nameOption: Option[String],
      xa: transactor.Transactor.Aux[IO, Unit]): Option[Long] = {
    nameOption match {
      case Some(value) =>
        (for {
          _ <- sql"CALL sp_gr_Nickname($value, @nn);".update.run
          nid <- sql"SELECT @nn;".query[Long].option
        } yield nid)
          .transact(xa)
          .unsafeRunSync() match {
          case Some(id) => Some(id)
          case None =>
            throw new DBException(s"Failed to add or resolve nickname: $value")
        }
      case None => None
    }

  }

  private def addOrResolveHardware(
      name: String,
      xa: transactor.Transactor.Aux[IO, Unit]): Long = {
    (for {
      _ <- sql"CALL sp_gr_Hardware($name, @hw);".update.run
      hid <- sql"SELECT @hw;".query[Long].option
    } yield hid)
      .transact(xa)
      .unsafeRunSync() match {
      case Some(value) => value
      case None =>
        throw new DBException(s"Failed to add or resolve hardware $name")
    }
  }

  private def addOrResolveVersion(
      name: String,
      xa: transactor.Transactor.Aux[IO, Unit]): Long = {
    (for {
      _ <- sql"CALL sp_gr_Version($name, @ver);".update.run
      vid <- sql"SELECT @ver;".query[Long].option
    } yield vid)
      .transact(xa)
      .unsafeRunSync() match {
      case Some(value) => value
      case None =>
        throw new DBException(s"Failed to add or resolve version $name")
    }
  }

  def resolveProgram(hash: String,
                     numLabels: Long,
                     xa: transactor.Transactor.Aux[IO, Unit]): Option[Long] = {
    sql"SELECT id FROM programs WHERE num_labels = $numLabels AND src_hash = $hash"
      .query[Long]
      .option
      .transact(xa)
      .unsafeRunSync()
  }

  def addOrResolveProgram(filename: java.nio.file.Path,
                          hash: String,
                          numLabels: Long,
                          xa: transactor.Transactor.Aux[IO, Unit]): Long = {
    val name = filename.getFileName.toString
    (for {
      _ <- sql"CALL sp_gr_Program($name, $hash, $numLabels, @pid);".update.run
      pid <- sql"SELECT @pid;".query[Long].option
    } yield pid)
      .transact(xa)
      .unsafeRunSync() match {
      case Some(value) => value
      case None =>
        throw new DBException(s"Failed to add or resolve program $name")
    }
  }

  case class StoredComponent(id: Long,
                             programID: Long,
                             contextName: String,
                             specType: SpecType,
                             specIndex: Long,
                             exprType: ExprType,
                             exprIndex: Long,
                             dateAdded: String)

  def resolveComponent(
      programID: Long,
      astLabel: ASTLabel,
      xa: transactor.Transactor.Aux[IO, Unit]): Option[Long] = {
    val contextName = astLabel.parent match {
      case Left(value)  => value.name
      case Right(value) => value.name
    }
    sql"SELECT id FROM components WHERE context_name = $contextName AND spec_index = ${astLabel.specIndex} AND spec_type = ${astLabel.specType} AND expr_index = ${astLabel.exprIndex} AND expr_type = ${astLabel.exprType} AND program_id = $programID;"
      .query[Long]
      .option
      .transact(xa)
      .unsafeRunSync()
  }

  def addOrResolveComponent(programID: Long,
                            astLabel: ASTLabel,
                            xa: transactor.Transactor.Aux[IO, Unit]): Long = {
    val contextName = astLabel.parent match {
      case Left(value)  => value.name
      case Right(value) => value.name
    }
    (for {
      _ <- sql"CALL sp_gr_Component($programID, $contextName, ${astLabel.specType}, ${astLabel.specIndex}, ${astLabel.exprType}, ${astLabel.exprIndex}, @comp);".update.run
      cid <- sql"SELECT @comp;".query[Long].option
    } yield cid).transact(xa).unsafeRunSync() match {
      case Some(value) => value
      case None =>
        throw new DBException(
          s"Failed to add or resolve component ${astLabel.hash}")
    }
  }

  def resolvePermutation(permID: Long,
                         xa: DBConnection): Option[Permutation] = {
    sql"SELECT * FROM permutations WHERE id = $permID;"
      .query[Permutation]
      .option
      .transact(xa)
      .unsafeRunSync()
  }

  def addOrResolvePermutation(programID: Long,
                              permutationHash: Array[Byte],
                              xa: DBConnection): Long = {

    (for {
      _ <- sql"""CALL sp_gr_Permutation($programID, $permutationHash, @perm);""".update.run
      pid <- sql"""SELECT @perm;""".query[Long].option
    } yield pid).transact(xa).unsafeRunSync() match {
      case Some(value) => value
      case None =>
        throw new DBException(
          s"Failed to add or resolve permutation $permutationHash")
    }
  }

  def getNumberOfPaths(programID: Long, xa: DBConnection): Int = {
    sql"SELECT COUNT(*) FROM paths WHERE program_id = $programID"
      .query[Int]
      .option
      .transact(xa)
      .unsafeRunSync() match {
      case Some(value) => value
      case None =>
        throw new DBException(
          s"Failed to query for number of paths corresponding to a given program.")
    }
  }

  def updateStaticProfiling(id: Identity,
                            pid: Long,
                            iterations: Int,
                            vOut: TimedVerification,
                            cOut: Performance,
                            xa: transactor.Transactor.Aux[IO, Unit]): Unit = {
    val tr = vOut.translation
    val vf = vOut.verification
    val in = vOut.instrumentation
    val cp = cOut
    val profiling = vOut.output.profiling
    val nn = id.nid match {
      case Some(value) => value.toString
      case None        => "NULL"
    }
    (for {
      _ <- sql"INSERT INTO measurements (iter, ninety_fifth, fifth, median, mean, stdev, minimum, maximum) VALUES ($iterations, ${tr.ninetyFifth}, ${tr.fifth}, ${tr.median}, ${tr.mean}, ${tr.stdev}, ${tr.minimum}, ${tr.maximum});".update.run
      tr_id <- sql"SELECT LAST_INSERT_ID();".query[Long].unique
      _ <- sql"INSERT INTO measurements (iter, ninety_fifth, fifth, median, mean, stdev, minimum, maximum) VALUES ($iterations, ${vf.ninetyFifth}, ${vf.fifth}, ${vf.median}, ${vf.mean}, ${vf.stdev}, ${vf.minimum}, ${vf.maximum});".update.run
      vf_id <- sql"SELECT LAST_INSERT_ID();".query[Long].unique
      _ <- sql"INSERT INTO measurements (iter, ninety_fifth, fifth, median, mean, stdev, minimum, maximum) VALUES ($iterations, ${in.ninetyFifth}, ${in.fifth}, ${in.median}, ${in.mean}, ${in.stdev}, ${in.minimum}, ${in.maximum});".update.run
      in_id <- sql"SELECT LAST_INSERT_ID();".query[Long].unique
      _ <- sql"INSERT INTO measurements (iter, ninety_fifth, fifth, median, mean, stdev, minimum, maximum) VALUES ($iterations, ${cp.ninetyFifth}, ${cp.fifth}, ${cp.median}, ${cp.mean}, ${cp.stdev}, ${cp.minimum}, ${cp.maximum});".update.run
      cp_id <- sql"SELECT LAST_INSERT_ID();".query[Long].unique
      r <- sql"CALL sp_UpdateStatic(${id.vid}, ${id.hid}, $nn, $pid, $tr_id, $vf_id, $in_id, $cp_id, ${profiling.nConjuncts}, ${profiling.nConjunctsEliminated});".update.run
    } yield r).transact(xa).unsafeRunSync()
  }

  def reserveProgramForMeasurement(
      id: Identity,
      workloads: List[Int],
      xa: DBConnection): Option[ReservedProgram] = {
    val nn = id.nid match {
      case Some(value) => value.toString
      case None        => "NULL"
    }
    for (i <- workloads) {
      val reserved = (for {
        _ <- sql"""CALL sp_ReservePermutation(${id.vid}, ${id.hid}, $nn, $i, @perm, @mode);""".update.run
        perm <- sql"""SELECT * FROM permutations WHERE id = (SELECT @perm);"""
          .query[Option[Permutation]]
          .option
        mode <- sql"""SELECT @mode;"""
          .query[Option[String]]
          .option
      } yield (perm, mode)).transact(xa).unsafeRunSync()

      reserved._1 match {
        case Some(permutationReserved) =>
          reserved._2 match {
            case Some(modeReserved) =>
              return Some(
                ReservedProgram(permutationReserved.get, i, modeReserved.get))
            case None =>
          }
        case None =>
      }
    }
    None
  }

  def completeProgramMeasurement(id: Identity,
                                 permID: Long,
                                 iterations: Int,
                                 p: Performance,
                                 xa: DBConnection) = {
    val nicknameResolved = id.nid match {
      case Some(value) => value.toString
      case None        => "NULL"
    }
    (for {
      _ <- sql"INSERT INTO measurements (iter, ninety_fifth, fifth, median, mean, stdev, minimum, maximum) VALUES ($iterations, ${p.ninetyFifth}, ${p.fifth}, ${p.median}, ${p.mean}, ${p.stdev}, ${p.minimum}, ${p.maximum});".update.run
      mid <- sql"SELECT LAST_INSERT_ID();".query[Long].unique
      r <- sql"UPDATE dynamic_performance SET measurement_id = $mid, last_updated = CURRENT_TIMESTAMP WHERE permutation_id = $permID AND version_id = ${id.vid} AND nickname_id = $nicknameResolved AND hardware_id = ${id.hid};".update.run
    } yield r).transact(xa).unsafeRunSync()
  }

  def containsPath(programID: Long,
                   pathHash: Array[Byte],
                   xa: DBConnection): Boolean = {
    sql"SELECT COUNT(*) > 0 FROM paths WHERE program_id = $programID AND path_hash = $pathHash;"
      .query[Boolean]
      .unique
      .transact(xa)
      .unsafeRunSync()
  }

  class PathQueryCollection(hash: Array[Byte],
                            programID: Long,
                            bottomPermutationID: Long) {

    private case class Step(permID: Long,
                            levelID: Long,
                            componentID: Long,
                            pathID: Long)

    private val steps = mutable.ListBuffer[(Long, Long)]()

    def addStep(perm: Long, componentID: Long): Unit = {
      steps += Tuple2(perm, componentID)
    }

    def exec(xa: DBConnection): Unit = {
      val massUpdate = for {
        _ <- sql"""INSERT INTO paths
             (path_hash, program_id)
         VALUES
             ($hash, $programID);""".update.run
        id <- sql"SELECT LAST_INSERT_ID()".query[Long].unique
        _ <- sql"INSERT INTO steps (permutation_id, level_id, path_id) VALUES ($bottomPermutationID, 0, $id)".update.run
        v <- Update[Step](
          s"INSERT INTO steps (permutation_id, level_id, component_id, path_id) VALUES (?, ?, ?, ?)")
          .updateMany(
            this.steps.indices
              .map(i => Step(this.steps(i)._1, i + 1, this.steps(i)._2, id))
              .toList)
      } yield v
      massUpdate.transact(xa).unsafeRunSync()
    }
  }

  def logException(id: Identity,
                   reserved: ReservedProgram,
                   mode: ErrorType,
                   errText: String,
                   timeElapsedSeconds: Long,
                   conn: DBConnection): Unit = {
    val nn = id.nid match {
      case Some(value) => value.toString
      case None        => "NULL"
    }
    (for {
      _ <- sql"CALL sp_gr_Error($errText, $timeElapsedSeconds, $mode, @eid)".update.run
      eid <- sql"SELECT @eid".query[Long].unique
      _ <- sql"UPDATE static_performance SET error_id = $eid WHERE hardware_id = ${id.hid} AND version_id = ${id.vid} AND nickname_id = $nn AND permutation_id = ${reserved.perm.id}".update.run
      u <- sql"UPDATE dynamic_performance SET error_id = $eid WHERE hardware_id = ${id.hid} AND version_id = ${id.vid} AND nickname_id = $nn AND permutation_id = ${reserved.perm.id}".update.run
    } yield u).transact(conn).unsafeRunSync()
  }

  def listPerformanceResults(conn: DBConnection): List[CompletionMetadata] = {
    sql"""SELECT version_name, src_filename, type, total_completed, total_perms
    FROM (SELECT program_id, COUNT(permutations.id) as total_perms
      FROM permutations
      INNER JOIN programs p on permutations.program_id = p.id

      GROUP BY program_id) as tblA
    INNER JOIN (SELECT program_id, src_filename, version_name, type, COUNT(measurement_id) as total_completed
    FROM versions
      INNER JOIN dynamic_performance dp on versions.id = dp.version_id
    INNER JOIN permutations p on dp.permutation_id = p.id
    INNER JOIN programs p2 on p.program_id = p2.id
    INNER JOIN dynamic_measurement_types dmt on dp.dynamic_measurement_type = dmt.id

    WHERE measurement_id IS NOT NULL
    GROUP BY program_id, src_filename, version_name, type) as tblB
      on tblA.program_id = tblB.program_id"""
      .query[CompletionMetadata]
      .to[List]
      .transact(conn)
      .unsafeRunSync()
  }
}