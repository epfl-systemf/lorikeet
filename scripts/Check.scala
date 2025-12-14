//> using scala 3.7.4

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
import scala.util.matching.Regex

object CheckTool:

  sealed trait CheckResult:
    val studentId: String

  case class NoSubmission(studentId: String) extends CheckResult
  case class MissingFiles(studentId: String, attempt: Int, files: Seq[String])
      extends CheckResult
  case class CompileError(studentId: String, attempt: Int) extends CheckResult
  case class IssuesFound(
      studentId: String,
      attempt: Int,
      issues: Map[String, Int]
  ) extends CheckResult
  case class Success(studentId: String, attempt: Int) extends CheckResult

  case class Config(
      labDir: Path,
      submissionsDir: Path,
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
      if (!compile(cfg.labDir))
        return logAndReturn(CompileError(studentId, attempt))

      // Format and snapshot original
      formatCode(cfg.labDir)
      Files.copy(
        targetFilePath,
        tmpOriginal,
        StandardCopyOption.REPLACE_EXISTING
      )

      // Linting check
      val (lintCode, lintOut) = runScalafix(cfg.labDir)
      val rules = processLintReport(lintOut, lintReport, cfg.labDir)

      // Apply fixes, reformat
      formatCode(cfg.labDir)
      Files.copy(
        targetFilePath,
        tmpRefactored,
        StandardCopyOption.REPLACE_EXISTING
      )

      // Compare results
      val hasDiff = diff(tmpOriginal, tmpRefactored, diffOut).isDefined

      val issuesFound = Files.exists(lintReport) || hasDiff
      if (issuesFound) then
        val issueCounts = rules.groupBy(identity).view.mapValues(_.size).toMap
        logAndReturn(IssuesFound(studentId, attempt, issueCounts))
      else logAndReturn(Success(studentId, attempt))

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

  def runScalafix(labDir: Path): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      (s: String) => output.append(s).append('\n'),
      (e: String) => output.append(e).append('\n')
    )
    val command =
      Seq(
        "sbt",
        "--client",
        "-DLINT_LEVEL=full",
        "scalafix"
      )
    val exitCode = Process(command, labDir.toFile).!(logger)
    (exitCode, output.toString())
  }

  def compile(labDir: Path): Boolean = {
    val exitCode = execCommand(
      Seq("sbt", "--client", "-Dsbt.log.noformat=true", "compile"),
      labDir
    )
    exitCode == 0
  }

  def formatCode(
      labDir: Path
  ): Unit = {
    execCommand(
      Seq("sbt", "--client", "-Dsbt.log.noformat=true", "scalafmt"),
      labDir
    )
  }

  case class LintReportItem(
      path: String,
      line: Int,
      col: Int,
      ruleName: String,
      message: String,
      code: String
  )

  sealed trait LineType
  case class ErrorHeader(
      path: String,
      line: Int,
      col: Int,
      ruleName: String,
      message: String
  ) extends LineType
  case class CodeLine(code: String) extends LineType
  case class PointerLine(pointer: String) extends LineType
  case object OtherLine extends LineType

  private val ErrorHeaderPattern: Regex =
    """\[error\]\s+(\S+):(\d+):(\d+):\s+error:\s+\[ParsedRule\]\s+\[(.*?)\]\s+(.*)""".r
  private val CodeLinePattern: Regex =
    """\[error\](\s*.*)""".r
  private val PointerLinePattern: Regex =
    """\[error\](\s*\^+)""".r

  private def getLineType(line: String, rootPrefix: String): LineType = {
    line.replace(rootPrefix, "") match {
      case ErrorHeaderPattern(path, lineNum, colNum, ruleName, message) =>
        ErrorHeader(path, lineNum.toInt, colNum.toInt, ruleName, message.trim)
      case PointerLinePattern(pointer) =>
        PointerLine(pointer)
      case CodeLinePattern(code) =>
        CodeLine(code)
      case _ =>
        OtherLine
    }
  }

  case class IssueDetail(
      ruleName: String,
      message: String,
      path: String,
      line: Int,
      col: Int,
      codeLine: String,
      pointerLine: String
  )

  def processLintReport(
      output: String,
      reportFile: Path,
      root: Path
  ): Seq[String] = {
    val rootPrefix = root.toString + File.separator
    val lines = output
      .replaceAll("\\e\\[[\\d;]*[^\\d;]", "") // Remove ANSI codes
      .split('\n')
      .toSeq
      .filter(_.startsWith("[error]"))
      .map(line => getLineType(line, rootPrefix))
      .zipWithIndex

    val issueBlock: Seq[IssueDetail] = lines.flatMap {
      case (header @ ErrorHeader(path, line, col, ruleName, message), i) =>
        val codeLine =
          lines
            .drop(i + 1)
            .headOption
            .collect { case (CodeLine(code), _) => code }
            .getOrElse("")
        val pointerLine =
          lines
            .drop(i + 2)
            .headOption
            .collect { case (PointerLine(pointer), _) => pointer }
            .getOrElse("")

        Some(
          IssueDetail(
            ruleName,
            message,
            path,
            line,
            col,
            codeLine,
            pointerLine
          )
        )
      case _ =>
        None
    }

    val foundRules = issueBlock.map(_.ruleName)

    val report = issueBlock
      .groupBy( // rule name and message
        issue => (issue.ruleName, issue.message)
      )
      .map { case ((ruleName, message), issues) =>
        val reportBlock = new StringBuilder
        reportBlock.append(
          s"[${ruleName}]\n${message} (${issues.length} occurrences)\n\n"
        )
        issues.foreach { issue =>
          reportBlock.append(s"${issue.path}:${issue.line}:${issue.col}\n")
          reportBlock.append(s"${issue.codeLine}\n")
          if (issue.pointerLine.nonEmpty) {
            reportBlock.append(s"${issue.pointerLine}\n")
          }
        }
        reportBlock.toString()
      }
      .toSeq
      .mkString("\n")

    if (report.nonEmpty)
      Files.write(
        reportFile,
        report.getBytes,
        StandardOpenOption.CREATE
      )
    else Files.deleteIfExists(reportFile)

    foundRules
  }

  def logAndReturn(
      result: CheckResult
  ): CheckResult = {
    val logMsg = result match {
      case NoSubmission(studentId) =>
        s"   -> ❓ MISSING: $studentId\n"
      case MissingFiles(studentId, attempt, files) =>
        s"   -> ❓ MISSING FILES: $studentId / $attempt -> ${files.mkString(", ")}\n"
      case CompileError(studentId, attempt) =>
        s"   -> ❌ ERROR:   $studentId / $attempt\n"
      case IssuesFound(studentId, attempt, issues) =>
        s"   -> ⚠️  ISSUES:  $studentId / $attempt -> ${issues
            .map { case (rule, count) => s"$rule ($count)" }
            .mkString(", ")}\n"
      case Success(studentId, attempt) =>
        s"   -> ✅ SUCCESS: $studentId / $attempt\n"
    }
    println(logMsg.trim)
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
    val output = new StringBuilder
    Process(
      Seq("diff", "-u", originalFile.toString, refactoredFile.toString)
    ).!(
      ProcessLogger(
        s => output.append(s).append('\n'),
        s => System.err.println(s)
      )
    )

    val diffOutput = output.toString().trim

    if (diffOutput.nonEmpty) {
      Files.write(
        diffOutputFile,
        diffOutput.getBytes(StandardCharsets.UTF_8),
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

    println(s"Diffs directory: ${cfg.diffDir}")
    println(s"Lint reports directory: ${cfg.lintDir}\n")
    println("Starting grading process...\n")

    val studentDirs = Files
      .list(cfg.submissionsDir)
      .iterator()
      .asScala
      .filter(Files.isDirectory(_))
      .toSeq

    val results: Seq[CheckResult] =
      studentDirs.map(dir => checkStudent(dir, cfg))

    val totalSubmissions = results.length
    val missingFiles = results.count {
      case MissingFiles(_, _, _) => true
      case _                     => false
    }
    val compileErrors = results.count {
      case CompileError(_, _) => true
      case _                  => false
    }
    val ruleMatches = results.count {
      case IssuesFound(_, _, _) => true
      case _                    => false
    }

    val globalMatches = results
      .collect { case IssuesFound(_, _, issues) =>
        issues
      }
      .flatMap(_.toSeq)
      .groupMapReduce(_._1)(_._2)(_ + _)
      .toSeq
      .sortBy(-_._2)

    val studentMatches = results
      .collect { case IssuesFound(studentId, _, issues) =>
        issues.map(_._1)
      }
      .flatten
      .groupMapReduce(identity)(_ => 1)(_ + _)
      .toSeq
      .sortBy(-_._2)

    println("\n--- SUMMARY ---")
    val summary = s"""Total submissions: $totalSubmissions
Submissions with missing file: $missingFiles
Submissions with compile errors: $compileErrors
Submissions failing check: $ruleMatches
"""
    println(summary)

    println("--- STATISTICS ---")
    println("Submissions with Matches:")
    studentMatches.foreach { case (rule, count) =>
      println(f"  $rule: $count")
    }
    println("Total Rule Matches:")
    globalMatches.foreach { case (rule, count) =>
      println(f"  $rule: $count")
    }

    println(s"\nGrading complete.")
  }
