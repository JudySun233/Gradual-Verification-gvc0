package gvc

import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import sys.process._
import scala.language.postfixOps

sealed trait CC0Architecture { val name: String }
object CC0Architecture {
  case object Arch32 { val name = "32" }
  case object Arch64 { val name = "64" }
}
case class CC0Options(
    compilerPath: String = "cc0",
    verbose: Boolean = false,
    libraries: List[String] = Nil,
    includeDirs: List[String] = Nil,
    execArgs: List[String] = Nil,
    var compilerArgs: List[String] = Nil,
    warnings: List[String] = Nil,
    dumpAST: Boolean = false,
    output: Option[String] = None,
    warn: Boolean = false,
    exec: Boolean = false,
    version: Boolean = false,
    log: Boolean = true,
    dynCheck: Boolean = false,
    purityCheck: Boolean = true,
    saveIntermediateFiles: Boolean = false,
    genBytecode: Boolean = false,
    copy: Boolean = false,
    standard: Option[String] = None,
    runtime: Option[String] = None,
    architecture: Option[CC0Architecture] = None,
    onlyTypecheck: Boolean = false,
    includeRuntime: Boolean = false,
    inputFiles: List[String] = Nil
)

object CC0Wrapper {
  private val bundledResourcesDirectory = Paths
    .get(ClassLoader.getSystemResource(".").getPath)
    .toAbsolutePath
    .toString + "/"
  class CC0Exception(val message: String) extends RuntimeException {
    override def getMessage: String = message
  }

  def exec(sourceFile: String, options: CC0Options): Int = {
    val os = new ByteArrayOutputStream
    val command = formatCommand(sourceFile, options)
    val exitCode = (command #> os) !

    exitCode
  }

  case class CommandOutput(exitCode: Int, output: String)

  case class ExecutionOutput(
      exitCode: Int,
      output: String,
      perf: Option[Performance]
  )

  class Performance(
      median: Long,
      mean: Long,
      stdev: Long,
      min: Long,
      max: Long
  ) {
    override def toString: String = {
      s"$median,$mean,$stdev,$min,$max"
    }
  }

  def exec_output(
      sourceFile: String,
      options: CC0Options
  ): CommandOutput = {
    val os = new ByteArrayOutputStream
    val command = formatCommand(sourceFile, options)
    val exitCode = (command #> os).!
    os.close()
    CommandOutput(exitCode, os.toString("UTF-8"))
  }

  private def formatCommand(
      sourceFile: String,
      options: CC0Options
  ): List[String] = {
    def flag(arg: String, flag: Boolean): Seq[String] =
      if (flag) Seq(arg) else Seq.empty
    def values(arg: String, values: Seq[String]): Seq[String] =
      values.flatMap(value =>
        if (arg.startsWith("--")) Seq(s"$arg=$value") else Seq(arg, value))
    def value(arg: String, value: Option[String]): Seq[String] =
      values(arg, value.toSeq)

    Seq(
      Seq(options.compilerPath),
      options.inputFiles.flatMap(value => Seq(value)),
      Seq(sourceFile),
      flag("--verbose", options.verbose),
      flag("--dyn-check", options.dynCheck),
      flag("--no-purity-check", !options.purityCheck),
      values("--library", options.libraries),
      values("-L", options.includeDirs ++ List(bundledResourcesDirectory)),
      values("-a", options.execArgs),
      value("--standard", options.standard),
      flag("--no-log", !options.log),
      flag("--dump-ast", options.dumpAST),
      flag("--save-files", options.saveIntermediateFiles),
      value("--runtime", options.runtime),
      flag("--bytecode", options.genBytecode),
      value("--bc-arch", options.architecture.map(_.name)),
      value("--output", options.output),
      values("-c", options.compilerArgs),
      flag("--warn", options.warn),
      flag("--exec", options.exec),
      flag("--only-typecheck", options.onlyTypecheck),
    ).flatten.toList
  }
}
