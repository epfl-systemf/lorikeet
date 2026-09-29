//> using scala 3.7.4
//> using dep com.lihaoyi::ujson:4.4.3

// --- CONFIGURATION ---
val SCAFFOLD_DIR = "scaffold_projects/find"
val SUBMISSIONS_DIR = "student-lab-submissions/2026/find"
val TARGET_FILES = Seq("src/main/scala/find/find.scala")

// val SCAFFOLD_DIR = "scaffold_projects/boids"
// val SUBMISSIONS_DIR = "student-lab-submissions/2024/boids/submissions"
// val TARGET_FILES = Seq("src/main/scala/boids/BoidLogic.scala")

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

  case class MissingFiles(studentId: String, files: Seq[String])
      extends CheckResult
  case class CompileError(studentId: String) extends CheckResult
  case class RewriteError(studentId: String) extends CheckResult
  case class ProcessingError(studentId: String) extends CheckResult
  case class IssuesFound(
      studentId: String,
      issues: Map[String, Int]
  ) extends CheckResult
  case class Success(studentId: String) extends CheckResult

  case class Config(
      labDir: Path,
      submissionsDir: Path,
      diffDir: Path,
      historyDir: Path,
      originalDir: Path,
      lintDir: Path,
      resultsFile: Path,
      tmpDir: Path,
      targetFiles: Seq[Path]
  )

  case class Rule(
      name: String,
      description: String
  )

  case class FileContext(
      fileName: String,
      artifactName: String,
      labPath: Path,
      subPath: Path,
      preSnap: Path,
      postSnap: Path
  )

  def checkStudent(studentDir: Path, cfg: Config): CheckResult = {
    val studentId = studentDir.getFileName.toString

    val lintReport = cfg.lintDir.resolve(s"$studentId.lint.txt")
    val historyTemp = cfg.tmpDir.resolve(s"$studentId-history")

    val basenames = cfg.targetFiles.map(_.getFileName.toString)
    val contexts = cfg.targetFiles.zipWithIndex.map { (labPath, index) =>
      val relativePath = cfg.labDir.relativize(labPath)
      val basename = labPath.getFileName.toString
      val duplicateName = basenames.count(_ == basename) > 1
      val nestedPath = studentDir.resolve(relativePath)
      val submittedPath =
        if duplicateName || Files.isRegularFile(nestedPath) then nestedPath
        else studentDir.resolve(basename)
      val artifactName =
        if duplicateName then s"$studentId-$index-$basename"
        else s"$studentId-$basename"
      FileContext(
        fileName = relativePath.toString,
        artifactName = artifactName,
        labPath = labPath,
        subPath = submittedPath,
        preSnap = cfg.tmpDir.resolve(s"$artifactName.pre"),
        postSnap = cfg.tmpDir.resolve(s"$artifactName.post")
      )
    }

    val missing =
      contexts.filter(ctx => !Files.isRegularFile(ctx.subPath)).map(_.fileName)
    if (missing.nonEmpty) then
      return logAndReturn(MissingFiles(studentId, missing))

    Files.createDirectories(historyTemp)
    clearDirectory(historyTemp)
    try {
      contexts.foreach { ctx =>
        Files.createDirectories(ctx.labPath.getParent)
        Files.copy(
          ctx.subPath,
          ctx.labPath,
          StandardCopyOption.REPLACE_EXISTING
        )
      }

      // Preserve the submitted source, then format before compiling so SemanticDB
      // positions describe exactly the source Lorikeet will inspect.
      contexts.foreach { ctx =>
        Files.copy(
          ctx.labPath,
          cfg.originalDir.resolve(ctx.artifactName),
          StandardCopyOption.REPLACE_EXISTING
        )
      }
      if (formatCode(cfg.labDir) != 0)
        return logAndReturn(CompileError(studentId))
      if (!compile(cfg.labDir))
        return logAndReturn(CompileError(studentId))
      contexts.foreach { ctx =>
        Files.copy(
          ctx.labPath,
          ctx.preSnap,
          StandardCopyOption.REPLACE_EXISTING
        )
      }

      // Linting check
      val (scalafixExit, scalafixOutput) = runScalafix(cfg.labDir, cfg, historyTemp)
      if (scalafixExit != 0) {
        System.err.println(scalafixOutput)
        return logAndReturn(ProcessingError(studentId))
      }
      val histories = Files.list(historyTemp)
      val historyFiles = try histories.iterator().asScala.toVector.sortBy(_.getFileName.toString)
      finally histories.close()
      if (historyFiles.size != contexts.size)
        return logAndReturn(ProcessingError(studentId))
      val rules = processHistoryLints(historyFiles, lintReport) ++
        historyFiles.flatMap { history =>
          ujson.read(Files.readString(history))("steps").arr.map(step =>
            Rule(step("rule").str, step("description").str)
          )
        }
      val bundledHistory = ujson.Obj(
        "schemaVersion" -> 1,
        "student" -> studentId,
        "files" -> ujson.Arr.from(historyFiles.map(path =>
          ujson.read(Files.readString(path))
        ))
      )
      Files.writeString(
        cfg.historyDir.resolve(s"$studentId.history.json"),
        bundledHistory.render(indent = 2)
      )

      // Apply fixes, reformat
      if (formatCode(cfg.labDir) != 0)
        return logAndReturn(ProcessingError(studentId))
      if (!compile(cfg.labDir))
        return logAndReturn(RewriteError(studentId))
      contexts.foreach { ctx =>
        Files.copy(
          ctx.labPath,
          ctx.postSnap,
          StandardCopyOption.REPLACE_EXISTING
        )
      }

      // Compare results
      val anyChange = contexts
        .map { ctx =>
          val diffOut = cfg.diffDir.resolve(ctx.artifactName + ".diff")
          diff(ctx.preSnap, ctx.postSnap, diffOut).isDefined
        }
        .contains(true)

      val issuesFound = Files.exists(lintReport) || anyChange
      if (issuesFound) then
        val issueCounts = rules.groupBy(_.name).view.mapValues(_.size).toMap
        logAndReturn(IssuesFound(studentId, issueCounts))
      else logAndReturn(Success(studentId))

    } catch {
      case e: Exception =>
        System.err.println(
          s"Internal error grading $studentId: ${e.getMessage}"
        )
        logAndReturn(ProcessingError(studentId))
    } finally {
      // Cleanup submission
      contexts.foreach { ctx =>
        Files.deleteIfExists(ctx.labPath)
        Files.deleteIfExists(ctx.preSnap)
        Files.deleteIfExists(ctx.postSnap)
      }
      clearDirectory(historyTemp)
      Files.deleteIfExists(historyTemp)
    }
  }

  private def clearDirectory(directory: Path): Unit = {
    val entries = Files.list(directory)
    try entries.iterator().asScala.foreach(Files.deleteIfExists(_))
    finally entries.close()
  }

  def runScalafix(
      labDir: Path,
      cfg: Config,
      historyDir: Path
  ): (Int, String) = {
    val output = new StringBuilder
    val logger = ProcessLogger(
      (s: String) => output.append(s).append('\n'),
      (e: String) => output.append(e).append('\n')
    )

    val fileArgs = cfg.targetFiles.map(f => s"--files=$f").mkString(" ")
    val historyProperty = ujson.Str(historyDir.toString).render()
    val command = Seq(
      "sbt",
      s";eval java.lang.System.setProperty(\"lorikeet.history.dir\", $historyProperty);scalafix MetaRule $fileArgs"
    )
    val exitCode = Process(
      command,
      labDir.toFile,
      "LORIKEET_HISTORY_DIR" -> historyDir.toString
    ).!(logger)
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
  ): Int = {
    execCommand(
      Seq("sbt", "--client", "-Dsbt.log.noformat=true", "scalafmt"),
      labDir
    )
  }

  def processHistoryLints(
      histories: Seq[Path],
      reportFile: Path
  ): Seq[Rule] = {
    val issues = histories.flatMap { history =>
      val value = ujson.read(Files.readString(history))
      val path = value("file").str
      value("lints").arr.map { lint =>
        IssueDetail(
          lint("rule").str,
          lint("description").str,
          path,
          lint("line").num.toInt,
          lint("column").num.toInt,
          lint("code").str,
          "^" * math.max(1, lint("end").num.toInt - lint("start").num.toInt)
        )
      }
    }
    writeLintReport(issues, reportFile)
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
    """\[error\]\s+(\S+):(\d+):(\d+):\s+error:\s+\[MetaRule\]\s+\[(.*?)\]\s+(.*)""".r
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
  ): Seq[Rule] = {
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

    writeLintReport(issueBlock, reportFile)
  }

  private def writeLintReport(
      issues: Seq[IssueDetail],
      reportFile: Path
  ): Seq[Rule] = {
    val foundRules = issues.map(issue => Rule(issue.ruleName, issue.message))
    val report = issues
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
      case MissingFiles(studentId, files) =>
        s"   -> ❓ MISSING FILES: $studentId -> ${files.mkString(", ")}\n"
      case CompileError(studentId) =>
        s"   -> ❌ ERROR:   $studentId\n"
      case RewriteError(studentId) =>
        s"   -> ❌ INVALID REWRITE: $studentId\n"
      case ProcessingError(studentId) =>
        s"   -> ❌ PROCESSING ERROR: $studentId\n"
      case IssuesFound(studentId, issues) =>
        s"   -> ⚠️  ISSUES:  $studentId -> ${issues
            .map { case (rule, count) => s"$rule ($count)" }
            .mkString(", ")}\n"
      case Success(studentId) =>
        s"   -> ✅ SUCCESS: $studentId\n"
    }
    println(logMsg.trim)
    result
  }

  def writeResults(results: Seq[CheckResult], resultsFile: Path): Unit = {
    val rows = results.map {
      case MissingFiles(id, files) =>
        ujson.Obj("student" -> id, "status" -> "missing_files", "files" -> ujson.Arr.from(files))
      case CompileError(id) =>
        ujson.Obj("student" -> id, "status" -> "compile_error")
      case RewriteError(id) =>
        ujson.Obj("student" -> id, "status" -> "rewrite_error")
      case ProcessingError(id) =>
        ujson.Obj("student" -> id, "status" -> "processing_error")
      case IssuesFound(id, _) =>
        ujson.Obj("student" -> id, "status" -> "issues")
      case Success(id) =>
        ujson.Obj("student" -> id, "status" -> "success")
    }
    Files.writeString(resultsFile, ujson.Arr.from(rows).render(indent = 2))
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

    val diffOutput = output.toString()

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
    val outputRoot = ROOT.resolve("feedback/feedback_automation/output")
    val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd_HH.mm.ss")
    val timestamp = LocalDateTime.now().format(formatter)

    val cfg = Config(
      labDir = ROOT.resolve(SCAFFOLD_DIR),
      submissionsDir = ROOT.resolve(SUBMISSIONS_DIR),
      diffDir = outputRoot.resolve(s"grading_diffs_$timestamp"),
      historyDir = outputRoot.resolve(s"grading_histories_$timestamp"),
      originalDir = outputRoot.resolve(s"grading_originals_$timestamp"),
      lintDir = outputRoot.resolve(s"grading_reports_$timestamp"),
      resultsFile = outputRoot.resolve(s"grading_results_$timestamp.json"),
      tmpDir = outputRoot.resolve(".tmp"),
      targetFiles = TARGET_FILES.map(f => ROOT.resolve(SCAFFOLD_DIR).resolve(f))
    )

    List(
      cfg.diffDir,
      cfg.historyDir,
      cfg.originalDir,
      cfg.lintDir,
      cfg.tmpDir
    ).foreach(x => Files.createDirectories(x))

    println(s"Diffs directory: ${cfg.diffDir}")
    println(s"Rewrite histories directory: ${cfg.historyDir}")
    println(s"Original sources directory: ${cfg.originalDir}")
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

    writeResults(results, cfg.resultsFile)

    val totalSubmissions = results.length
    val missingFiles = results.count {
      case MissingFiles(_, _) => true
      case _                  => false
    }
    val compileErrors = results.count {
      case CompileError(_) | ProcessingError(_) => true
      case _               => false
    }
    val rewriteErrors = results.count {
      case RewriteError(_) => true
      case _               => false
    }
    val ruleMatches = results.count {
      case IssuesFound(_, _) => true
      case _                 => false
    }

    val globalMatches = results
      .collect { case IssuesFound(_, issues) =>
        issues
      }
      .flatMap(_.toSeq)
      .groupMapReduce(_._1)(_._2)(_ + _)
      .toSeq
      .sortBy(-_._2)

    val studentMatches = results
      .collect { case IssuesFound(studentId, issues) =>
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
Submissions with invalid rewrites: $rewriteErrors
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
