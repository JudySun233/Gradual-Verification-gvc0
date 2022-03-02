package gvc

import gvc.parser.Parser
import fastparse.Parsed.{Failure, Success}
import gvc.analyzer._
import gvc.transformer._
import gvc.visualizer.{Bench, SamplingHeuristic, SamplingInfo, VerifiedOutput}
import gvc.weaver.Weaver
import viper.silicon.Silicon
import viper.silver.verifier

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import java.io.IOException
import sys.process._
import scala.language.postfixOps

case class OutputFileCollection(
    irFileName: String,
    silverFileName: String,
    c0FileName: String
)

object Main extends App {
  val cmdConfig = Config.fromCommandLineArgs(args.toList)
  cmdConfig.validate()
  run(cmdConfig)
  def run(config: Config): Unit = {
    val sourceFile = config.sourceFile.get
    val baseName =
      if (sourceFile.toLowerCase().endsWith(".c0"))
        sourceFile.slice(0, sourceFile.length() - 3)
      else sourceFile
    val irFileName = baseName + ".ir.c0"
    val silverFileName = baseName + ".vpr"
    val c0FileName = baseName + ".verified.c0"
    val fileNames =
      OutputFileCollection(irFileName, silverFileName, c0FileName)

    val inputSource = readFile(config.sourceFile.get)

    if (config.permute.isDefined) {
      Bench.run(
        inputSource,
        SamplingInfo(SamplingHeuristic.Random, 1),
        fileNames,
        config
      )
    } else {
      val verifiedOutput = verify(inputSource, fileNames)
      if (verifiedOutput.isDefined) {
        execute(verifiedOutput.get.c0Source, fileNames)
      }
    }
  }

  def readFile(file: String): String =
    try {
      Files.readString(Paths.get(file))
    } catch {
      case _: IOException => Config.error(s"Could not read file 'file'")
    }

  def writeFile(file: String, contents: String): Unit =
    try {
      Files.writeString(Paths.get(file), contents, StandardCharsets.UTF_8)
    } catch {
      case _: IOException => Config.error(s"Could not write file '${file}'")
    }

  def deleteFile(file: String): Unit =
    try {
      Files.delete(Paths.get(file))
    } catch {
      case _: IOException => Config.error(s"Could not delete file 'file'")
    }

  def dump(output: String): Nothing = {
    println(output)
    sys.exit(0)
  }

  def dumpC0(output: String): Nothing = {
    // Print runtime check information for debugging when dumping C0 output
    // This only happens after verification, so runtime checks have been initialized
    for ((exp, checks) <- viper.silicon.state.runtimeChecks.getChecks) {
      println("Runtime checks required for " + exp.toString + ":")
      println(
        checks
          .map(b =>
            s"  if ${if (b.branchInfo.isEmpty) "true"
            else
              b.branchInfo
                .map { case (branch, _, _) => branch }
                .map(c => "(" + c.toString() + ")")
                .mkString(" && ")}: ${b.checks.toString()}"
          )
          .mkString("\n")
      )
    }

    dump(output)
  }

  def generateIR(inputSource: String): IRGraph.Program = {
    val parsed = Parser.parseProgram(inputSource) match {
      case fail: Failure =>
        Config.error(s"Parse error:\n${fail.trace().longAggregateMsg}")
      case Success(value, _) => value
    }
    val errors = new ErrorSink()
    val resolved = Validator
      .validateParsed(parsed, errors)
      .getOrElse(
        Config.error(
          s"Errors:\n" +
            errors.errors.map(_.toString()).mkString("\n")
        )
      )
    GraphTransformer.transform(resolved)
  }

  def verify(
      inputSource: String,
      fileNames: OutputFileCollection
  ): Option[VerifiedOutput] = {
    val ir = generateIR(inputSource)

    if (cmdConfig.permute.isEmpty) {
      if (cmdConfig.dump.contains(Config.DumpIR))
        dump(GraphPrinter.print(ir, includeSpecs = true))
      else if (cmdConfig.saveFiles)
        writeFile(
          fileNames.irFileName,
          GraphPrinter.print(ir, includeSpecs = true)
        )
    }

    val reporter = viper.silver.reporter.StdIOReporter()
    val z3Exe = Config.resolveToolPath("z3", "Z3_EXE")
    val silicon = Silicon.fromPartialCommandLineArguments(
      Seq("--z3Exe", z3Exe),
      reporter,
      Seq()
    )
    silicon.start()

    val silver = IRGraphSilver.toSilver(ir)

    if (!cmdConfig.permute.isDefined) {
      if (cmdConfig.dump == Some(Config.DumpSilver)) dump(silver.toString())
      else if (cmdConfig.saveFiles)
        writeFile(fileNames.silverFileName, silver.toString())
    }

    silicon.verify(silver) match {
      case verifier.Success => ()
      case verifier.Failure(errors) =>
        val message = s"Verification errors:\n" +
          errors.map(_.readableMessage).mkString("\n")
        println(
          message
        )
        silicon.stop()
        return None
    }

    silicon.stop()
    if (cmdConfig.permute.isEmpty && cmdConfig.onlyVerify) sys.exit(0)

    Weaver.weave(ir, silver)

    val c0Source = GraphPrinter.print(ir, includeSpecs = false)
    if (cmdConfig.permute.isEmpty && cmdConfig.dump.contains(Config.DumpC0))
      dumpC0(c0Source)
    Some(VerifiedOutput(silver, c0Source))
  }
  def execute(
      verifiedSource: String,
      fileNames: OutputFileCollection
  ): Unit = {
    val outputExe = cmdConfig.output.getOrElse("a.out")

    // TODO: Figure out how we can use the actual resource
    // Since it is bundled in the JAR we have to extract it and put it somewhere
    val runtimeIncludeDir = Paths.get("src/main/resources").toAbsolutePath()

    val cc0Options = CC0Options(
      compilerPath = Config.resolveToolPath("cc0", "CC0_EXE"),
      saveIntermediateFiles = cmdConfig.saveFiles,
      output = Some(outputExe),
      includeDirs = List(runtimeIncludeDir.toString + "/")
    )

    // Always write the intermediate C0 file, but then delete it
    // if not saving intermediate files
    writeFile(fileNames.c0FileName, verifiedSource)
    val compilerExit =
      try {
        CC0Wrapper.exec(fileNames.c0FileName, cc0Options)
      } finally {
        if (!cmdConfig.saveFiles) deleteFile(fileNames.c0FileName)
      }

    if (compilerExit != 0) Config.error("Compilation failed")

    if (cmdConfig.exec) {
      val outputCommand = Paths.get(outputExe).toAbsolutePath.toString
      sys.exit(Seq(outputCommand) !)
    } else {
      sys.exit(0)
    }

  }

}
