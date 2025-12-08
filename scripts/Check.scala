import java.io.File
import java.nio.file.{
  Files,
  Path,
  Paths,
  StandardOpenOption,
  StandardCopyOption
}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.sys.process.{Process, ProcessLogger}
import scala.jdk.CollectionConverters._
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ArrayBuffer

object CheckTool:

  sealed trait CheckResult:
    val studentId: String

  case class NoSubmission(studentId: String) extends CheckResult
  case class MissingFiles(studentId: String, attempt: Int, files: Seq[String])
      extends CheckResult
  case class CompileError(studentId: String, attempt: Int) extends CheckResult
  case class IssuesFound(studentId: String, attempt: Int) extends CheckResult
  case class Success(studentId: String, attempt: Int) extends CheckResult

  case class Config(
      labDir: Path,
      submissionsDir: Path,
      logFile: Path,
      diffDir: Path,
      lintDir: Path,
      tmpDir: Path,
      targetFile: String,
      targetRelPath: Path
  )

  def checkStudent(studentDir: Path, cfg: Config): CheckResult = {
    val studentId = studentDir.getFileName.toString

    val attemptDir =
      latestAttemptDir(studentDir) match
        case Some(s) => s
        case None    => return NoSubmission(studentId)

    val attempt = attemptDir.getFileName.toString.toInt
    val submissionFile = attemptDir.resolve(cfg.targetFile)

    if (!Files.exists(submissionFile))
      return logAndReturn(
        cfg.logFile,
        MissingFiles(studentId, attempt, Seq(cfg.targetFile))
      )

    // Context for this specific attempt
    val targetFilePath =
      cfg.labDir.resolve(cfg.targetRelPath).resolve(cfg.targetFile)
    val tmpOriginal = cfg.tmpDir.resolve(s"$studentId.original")
    val tmpRefactored = cfg.tmpDir.resolve(s"$studentId.refactored")
    val diffOut = cfg.diffDir.resolve(s"$studentId-$attempt.diff")
    val lintReport = cfg.lintDir.resolve(s"$studentId-$attempt.lint.txt")

    try {
      Files.createDirectories(targetFilePath.getParent)
      Files.copy(
        submissionFile,
        targetFilePath,
        StandardCopyOption.REPLACE_EXISTING
      )

      // Compile
      if (!compile(cfg.labDir, cfg.logFile))
        return logAndReturn(cfg.logFile, CompileError(studentId, attempt))

      // Format and snapshot original
      formatCode(cfg.labDir, cfg.logFile)
      Files.copy(
        targetFilePath,
        tmpOriginal,
        StandardCopyOption.REPLACE_EXISTING
      )

      // Linting check
      val (lintCode, lintOut) = runScalafix(cfg.labDir, Seq("--check"))
      logProcessOutput(cfg.logFile, lintOut)
      processLintReport(lintOut, lintReport, cfg.labDir)

      // Apply fixes, reformat
      runScalafix(cfg.labDir, Seq())
      formatCode(cfg.labDir, cfg.logFile)
      Files.copy(
        targetFilePath,
        tmpRefactored,
        StandardCopyOption.REPLACE_EXISTING
      )

      // Compare results
      val hasDiff = diff(tmpOriginal, tmpRefactored, diffOut).isDefined

      val issuesFound = Files.exists(lintReport) || hasDiff
      if (issuesFound)
        logAndReturn(cfg.logFile, IssuesFound(studentId, attempt))
      else logAndReturn(cfg.logFile, Success(studentId, attempt))

    } catch {
      case e: Exception =>
        System.err.println(
          s"Internal error grading $studentId: ${e.getMessage}"
        )
        CompileError(studentId, attempt)
    } finally {
      // Cleanup attempt files
      List(targetFilePath, tmpOriginal, tmpRefactored).foreach(
        Files.deleteIfExists
      )
    }
  }

  def runScalafix(labDir: Path, args: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      (s: String) => output.append(s).append('\n'),
      (e: String) =>
        output.append(e).append('\n') // Capture errors in same buffer
    )
    val command =
      Seq("sbt", "--client", "-Dsbt.log.noformat=true", "scalafix") ++ args
    val exitCode = Process(command, labDir.toFile).!(logger)
    (exitCode, output.toString())
  }

  def compile(labDir: Path, logFile: Path): Boolean = {
    val exitCode = execCommand(
      Seq("sbt", "--client", "-Dsbt.log.noformat=true", "compile"),
      labDir,
      Some(logFile)
    )
    exitCode == 0
  }

  def formatCode(
      labDir: Path,
      logFile: Path
  ): Unit = {
    execCommand(
      Seq("sbt", "--client", "-Dsbt.log.noformat=true", "scalafmt"),
      labDir,
      Some(logFile)
    )
  }

  def processLintReport(output: String, reportFile: Path, root: Path): Unit = {
    val cleaned = output
      .split('\n')
      .filter(_.startsWith("[error]"))
      .filterNot(s => s.contains("Total time") || s.contains("ScalafixFailed"))
      .map(_.replace(root.toString + File.separator, ""))
      .mkString("\n")

    if (cleaned.nonEmpty)
      Files.write(reportFile, cleaned.getBytes, StandardOpenOption.CREATE)
    else Files.deleteIfExists(reportFile)
  }

  def logProcessOutput(logFile: Path, out: String): Unit =
    Files.write(logFile, (out + "\n").getBytes, StandardOpenOption.APPEND)

  def logAndReturn(
      logFile: Path,
      result: CheckResult
  ): CheckResult = {
    val logMsg = result match {
      case NoSubmission(studentId) =>
        s"   -> ❓ MISSING: $studentId\n"
      case MissingFiles(studentId, attempt, files) =>
        s"   -> ❓ MISSING FILES: $studentId / $attempt -> ${files.mkString(", ")}\n"
      case CompileError(studentId, attempt) =>
        s"   -> ❌ ERROR:   $studentId / $attempt\n"
      case IssuesFound(studentId, attempt) =>
        s"   -> ⚠️  ISSUES:  $studentId / $attempt\n"
      case Success(studentId, attempt) =>
        s"   -> ✅ SUCCESS: $studentId / $attempt\n"
    }
    println(logMsg.trim)
    Files.write(logFile, logMsg.getBytes, StandardOpenOption.APPEND)
    result
  }

  def latestAttemptDir(studentDir: Path): Option[Path] = {
    val attemptDirs = Files
      .list(studentDir)
      .iterator()
      .asScala
      .filter(p =>
        Files.isDirectory(p) && p.getFileName.toString.matches("\\d+")
      )
      .toSeq

    if (attemptDirs.isEmpty) None
    else {
      val sortedDirs = attemptDirs.sortWith((a, b) =>
        a.getFileName.toString.toInt < b.getFileName.toString.toInt
      )
      Some(sortedDirs.last)
    }
  }

  def diff(
      originalFile: Path,
      refactoredFile: Path,
      diffOutputFile: Path
  ): Option[String] = {
    val diffLines = ArrayBuffer[String]()
    Process(
      Seq("diff", "-u", originalFile.toString, refactoredFile.toString)
    ).!(
      ProcessLogger(s => diffLines += s, s => System.err.println(s))
    )

    if (diffLines.nonEmpty) {
      Files.write(
        diffOutputFile,
        diffLines.mkString("\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE
      )
      Some(diffOutputFile.toString)
    } else {
      None
    }
  }

  def execCommand(
      command: Seq[String],
      cwd: Path,
      outputStream: Option[Path] = None,
      printOutput: Boolean = false
  ): Int = {
    val logger = new StringBuilder

    val processLogger = ProcessLogger(
      (s: String) => {
        logger.append(s).append('\n')
        if (printOutput) then println(s)
      },
      (s: String) => {
        logger.append(s).append('\n')
        System.err.println(s)
      }
    )

    val exitCode =
      try {
        Process(command, cwd.toFile).!(processLogger)
      } catch {
        case e: Exception =>
          System.err.println(
            s"Error running command: ${command.mkString(" ")}. Exception: ${e.getMessage}"
          )
          -1
      }

    outputStream.foreach { logPath =>
      Files.write(
        logPath,
        (logger.toString + "\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND,
        StandardOpenOption.CREATE
      )
    }

    exitCode
  }

  @main
  def run(): Unit = {

    val ROOT: Path = Paths.get(".").toAbsolutePath.normalize()
    val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd_HH.mm.ss")
    val timestamp = LocalDateTime.now().format(formatter)

    // --- CONFIGURATION ---
    val LAB_DIR_NAME = "find"
    val SUBMISSIONS_DIR_NAME = "student-lab-submissions/2024/find/submissions"
    val TARGET_FILE = "find.scala"
    val TARGET_PATH =
      Paths.get("src", "main", "scala", "find") // Relative to LAB_DIR

    val cfg = Config(
      labDir = ROOT.resolve(LAB_DIR_NAME),
      submissionsDir = ROOT.resolve(SUBMISSIONS_DIR_NAME),
      logFile = ROOT.resolve(s"grading_log_$timestamp.txt"),
      diffDir = ROOT.resolve(s"grading_diffs_$timestamp"),
      lintDir = ROOT.resolve(s"grading_reports_$timestamp"),
      tmpDir = ROOT.resolve("tmp"),
      targetFile = TARGET_FILE,
      targetRelPath = TARGET_PATH
    )

    List(
      cfg.diffDir,
      cfg.lintDir,
      cfg.tmpDir
    ).foreach(x => Files.createDirectories(x))

    println(s"Logging to file: ${cfg.logFile}\n")
    println(s"Diffs directory: ${cfg.diffDir}")
    println(s"Lint reports directory: ${cfg.lintDir}\n")

    Files.write(
      cfg.logFile,
      "------------------------------------------------------\n".getBytes,
      StandardOpenOption.APPEND,
      StandardOpenOption.CREATE
    )

    val sbtProcess = Process(Seq("sbt", "compile"), cfg.labDir.toFile)
      .run(ProcessLogger(_ => ()))
    Runtime.getRuntime.addShutdownHook(
      new Thread {
        override def run(): Unit = {
          sbtProcess.destroy()
          Files.deleteIfExists(cfg.tmpDir)
        }
      }
    )
    Thread.sleep(10000)

    val studentDirs = Files
      .list(cfg.submissionsDir)
      .iterator()
      .asScala
      .filter(Files.isDirectory(_))
      .toSeq

    val results: Seq[CheckResult] =
      studentDirs.map(dir => checkStudent(dir, cfg))

    val TOTAL_SUBMISSIONS = results.length
    val MISSING_FILE_SUBMISSIONS = results.count {
      case MissingFiles(_, _, _) => true
      case _                     => false
    }
    val COMPILE_ERROR_SUBMISSIONS = results.count {
      case CompileError(_, _) => true
      case _                  => false
    }
    val RULE_MATCH_SUBMISSIONS = results.count {
      case IssuesFound(_, _) => true
      case _                 => false
    }

    println("\n--- SUMMARY ---")
    val summary = s"""Total submissions: $TOTAL_SUBMISSIONS
Submissions with missing file: $MISSING_FILE_SUBMISSIONS
Submissions with compile errors: $COMPILE_ERROR_SUBMISSIONS
Submissions failing check (issues found): $RULE_MATCH_SUBMISSIONS
-------------------------------------------------------
"""
    println(summary)
    Files.write(cfg.logFile, summary.getBytes, StandardOpenOption.APPEND)

    println(s"Grading complete. Final results summary in ${cfg.logFile}")

    sbtProcess.destroy()
  }
