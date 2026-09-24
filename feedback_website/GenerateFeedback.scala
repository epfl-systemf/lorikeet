//> using scala 3.7.4
//> using dep com.lihaoyi::ujson:4.4.3
//> using test.dep org.scalameta::munit:1.3.6

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{
  Files,
  Path,
  Paths,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.{CountDownLatch, Executors}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

final case class GeneratorConfig(
    data: Path,
    rules: Path,
    mockup: Path,
    output: Path,
    logs: Path,
    run: Option[String],
    publishLab: Option[String],
    deploymentRoot: Path,
    includeScalafmt: Boolean,
    serve: Boolean,
    port: Int
)

final case class LintIssue(
    name: String,
    message: String,
    path: String,
    line: Int,
    column: Int,
    code: String,
    width: Int
)

final case class DiffBlock(start: Int, before: Seq[String], after: Seq[String])
final case class FeedbackItem(
    rule: String,
    file: String,
    line: Int,
    column: Int
)
final case class ReportPage(
    filename: String,
    title: String,
    page: String,
    count: Int,
    counts: Map[String, Int]
)
final case class OverviewEntry(
    filename: String,
    submission: String,
    run: String,
    counts: Map[String, Int]
)
final case class TemplateSet(
    templates: mutable.LinkedHashMap[String, ujson.Obj],
    lookup: Map[String, ujson.Obj]
)
final case class LogIssue(
    id: String,
    rule: String,
    file: String,
    line: Int,
    column: Int
)
final case class LogEvent(
    eventId: String,
    sessionId: String,
    reportId: String,
    submission: String,
    run: String,
    eventType: String,
    timestamp: String,
    issue: LogIssue,
    rating: Option[String],
    receivedAt: Option[String] = None
)
final case class SummaryRow(
    sessionId: String,
    reportId: String,
    submission: String,
    run: String,
    issue: LogIssue,
    var viewed: Boolean = false,
    var firstViewedAt: String = "",
    var lastViewedAt: String = "",
    var viewCount: Int = 0,
    var rated: Boolean = false,
    var rating: String = "",
    var ratedAt: String = "",
    var ratingOrder: Option[(String, String, String)] = None
)

final class RunningReviewServer(
    val server: HttpServer,
    executor: java.util.concurrent.ExecutorService
) extends AutoCloseable:
  def port: Int = server.getAddress.getPort

  override def close(): Unit =
    server.stop(1)
    executor.shutdown()

object GenerateFeedback:
  private val RequiredTemplateFields = Seq(
    "title",
    "location",
    "what_to_improve_title",
    "explanation",
    "message",
    "suggested_rewrite_title",
    "rewrite_help",
    "rewrite_code",
    "no_rewrite"
  )
  private val TemplateValues = Seq(
    "file",
    "line",
    "column",
    "rule_name",
    "rule_message",
    "rewrite",
    "original_code"
  )
  private val Placeholder = raw"\{\{\s*(\w+)\s*\}\}".r
  private val LintHeading = raw"\[[^\]]+\]".r
  private val LintLocation = raw"(.+):(\d+):(\d+)".r
  private val Pointer = raw"\s*\^+".r
  private val Hunk = raw"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*".r
  private val LabName = raw"[A-Za-z0-9][A-Za-z0-9_-]{0,63}".r
  private val ExcludedDirectories =
    Set(".git", "node_modules", "target", ".scala-build")

  def parseLint(text: String): Seq[LintIssue] =
    val lines = text.linesIterator.toVector
    val issues = mutable.ArrayBuffer.empty[LintIssue]
    var name: Option[String] = None
    var message: Option[String] = None
    var index = 0
    while index < lines.length do
      lines(index) match
        case LintHeading() =>
          name = Some(lines(index).drop(1).dropRight(1))
          index += 1
          if index >= lines.length then
            throw IllegalArgumentException(
              "Missing description after rule heading"
            )
          message = Some(
            lines(index).replaceFirst(raw"\s*\(\d+ occurrences\)$$", "").trim
          )
        case LintLocation(path, row, column) =>
          if name.isEmpty || index + 1 >= lines.length then
            throw IllegalArgumentException(
              "Issue location without rule or code"
            )
          val code = lines(index + 1)
          val pointer = lines.lift(index + 2).getOrElse("")
          val width =
            if Pointer.matches(pointer) then pointer.trim.length else 1
          issues += LintIssue(
            name.get,
            message.getOrElse(""),
            path,
            row.toInt,
            column.toInt,
            code,
            width
          )
          index += (if pointer.contains("^") then 2 else 1)
        case line if line.trim.nonEmpty =>
          throw IllegalArgumentException("Unexpected lint report line: " + line)
        case _ => ()
      index += 1
    issues.toSeq

  def parseDiff(text: String): (Map[Int, String], Seq[DiffBlock]) =
    val original = mutable.TreeMap.empty[Int, String]
    val blocks = mutable.ArrayBuffer.empty[DiffBlock]
    var oldLine: Option[Int] = None
    var newLine = 0
    var oldLeft = 0
    var newLeft = 0
    var blockStart = 0
    var before = mutable.ArrayBuffer.empty[String]
    var after = mutable.ArrayBuffer.empty[String]

    def flush(): Unit =
      if before.nonEmpty || after.nonEmpty then
        blocks += DiffBlock(blockStart, before.toSeq, after.toSeq)
        before = mutable.ArrayBuffer.empty
        after = mutable.ArrayBuffer.empty

    text.linesIterator.foreach {
      case Hunk(oldStart, oldCount, newStart, newCount) =>
        if oldLeft != 0 || newLeft != 0 then
          throw IllegalArgumentException("Truncated diff hunk")
        flush()
        oldLine = Some(oldStart.toInt)
        newLine = newStart.toInt
        oldLeft = Option(oldCount).fold(1)(_.toInt)
        newLeft = Option(newCount).fold(1)(_.toInt)
      case line if line.startsWith("\\ No newline")                  => ()
      case line if oldLine.isEmpty || (oldLeft == 0 && newLeft == 0) =>
        flush()
        if line.nonEmpty && !Seq("--- ", "+++ ", "diff ", "index ").exists(
            line.startsWith
          )
        then throw IllegalArgumentException("Unexpected diff content: " + line)
      case line if line.startsWith(" ") =>
        flush()
        original(oldLine.get) = line.drop(1)
        oldLine = Some(oldLine.get + 1)
        newLine += 1
        oldLeft -= 1
        newLeft -= 1
      case line if line.startsWith("-") || line.startsWith("+") =>
        if before.isEmpty && after.isEmpty then blockStart = oldLine.get
        if line.startsWith("-") then
          original(oldLine.get) = line.drop(1)
          before += line.drop(1)
          oldLine = Some(oldLine.get + 1)
          oldLeft -= 1
        else
          after += line.drop(1)
          newLine += 1
          newLeft -= 1
      case line =>
        throw IllegalArgumentException("Unexpected diff hunk line: " + line)
    }
    flush()
    while !text.endsWith("\n") && oldLeft == newLeft && oldLeft > 0 do
      original(oldLine.get) = ""
      oldLine = Some(oldLine.get + 1)
      newLine += 1
      oldLeft -= 1
      newLeft -= 1
    if oldLeft != 0 || newLeft != 0 then
      throw IllegalArgumentException("Truncated diff hunk")
    (original.toMap, blocks.toSeq)

  def validateTemplates(value: ujson.Value): TemplateSet =
    val entries = value.obj
    if entries.isEmpty then
      throw IllegalArgumentException("Templates must be a nonempty object")
    val templates = mutable.LinkedHashMap.empty[String, ujson.Obj]
    val lookup = mutable.LinkedHashMap.empty[String, ujson.Obj]
    entries.foreach { (name, rawTemplate) =>
      val template = rawTemplate match
        case objectValue: ujson.Obj => objectValue
        case _                      =>
          throw IllegalArgumentException("Expected a text template for " + name)
      RequiredTemplateFields.foreach { field =>
        template.value.get(field) match
          case Some(_: ujson.Str) => ()
          case _                  =>
            throw IllegalArgumentException(s"$name: missing text field $field")
      }
      val aliases = template.value.get("aliases") match
        case None                   => Seq.empty
        case Some(array: ujson.Arr) =>
          array.value.map {
            case ujson.Str(alias) => alias
            case _                =>
              throw IllegalArgumentException(
                s"$name: aliases must be a list of rule names"
              )
          }.toSeq
        case _ =>
          throw IllegalArgumentException(
            s"$name: aliases must be a list of rule names"
          )
      (name +: aliases).foreach { alias =>
        if lookup.contains(alias) then
          throw IllegalArgumentException(
            "Rule name belongs to multiple templates: " + alias
          )
        lookup(alias) = template
      }
      fillTemplate(template, TemplateValues.map(_ -> "").toMap)
      templates(name) = template
    }
    TemplateSet(templates, lookup.toMap)

  def loadTemplates(config: GeneratorConfig): TemplateSet =
    val existing = validateTemplates(ujson.read(read(config.rules)))
    val aligned = mutable.LinkedHashMap.empty[String, ujson.Value]
    projectRuleNames(config).foreach { name =>
      val template = existing.templates
        .get(name)
        .map(copyObject)
        .getOrElse(defaultTemplate(name))
      template.value("title") = ujson.Str(name)
      template.value.remove("aliases")
      aligned(name) = template
    }
    validateTemplates(ujson.Obj.from(aligned))

  private def projectRuleNames(config: GeneratorConfig): Seq[String] =
    val root = config.rules.toAbsolutePath.normalize().getParent.getParent
    val pattern = java.util.regex.Pattern.compile(
      "(?s)\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|//[^\\n]*|\\#[^\\n]*|\\bname\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\")"
    )
    val names = mutable.LinkedHashSet.empty[String]
    Using.resource(Files.walk(root)) { paths =>
      paths.iterator.asScala
        .filter(path =>
          Files.isRegularFile(path) && path.toString.endsWith(".lorikeet.conf")
        )
        .filterNot { path =>
          root
            .relativize(path)
            .iterator
            .asScala
            .exists(part => ExcludedDirectories(part.toString))
        }
        .toSeq
        .sortBy(_.toString)
        .foreach { path =>
          val matcher = pattern.matcher(read(path))
          while matcher.find() do
            if matcher.group(1) != null then
              names += ujson.read(matcher.group(1)).str
        }
    }
    if names.isEmpty then
      throw IllegalArgumentException(
        "No rules found in project .lorikeet.conf files"
      )
    names.toSeq

  private def defaultTemplate(name: String): ujson.Obj = ujson.Obj(
    "title" -> name,
    "location" -> "{{file}}:{{line}}:{{column}}",
    "what_to_improve_title" -> "What to improve",
    "explanation" -> "",
    "message" -> "{{rule_message}}",
    "suggested_rewrite_title" -> "Suggested rewrite",
    "rewrite_help" -> "",
    "rewrite_code" -> "{{rewrite}}",
    "no_rewrite" -> ""
  )

  private def resolveRule(
      name: String,
      lookup: Map[String, ujson.Obj]
  ): ujson.Obj =
    lookup.getOrElse(name, defaultTemplate(name))

  private def fillTemplate(
      template: ujson.Obj,
      values: Map[String, Any]
  ): Map[String, String] =
    template.value.collect { case (key, ujson.Str(value)) =>
      key -> Placeholder.replaceAllIn(
        value,
        matched =>
          values
            .get(matched.group(1))
            .map(_.toString)
            .getOrElse(
              throw IllegalArgumentException(
                "Unknown template placeholder: " + matched.group(1)
              )
            )
      )
    }.toMap

  private def fallbackHistory(
      path: String,
      lines: Map[Int, String],
      blocks: Seq[DiffBlock],
      issues: Seq[LintIssue]
  ): ujson.Obj =
    val source = mutable.ArrayBuffer.empty[String]
    var previous: Option[Int] = None
    lines.toSeq.sortBy(_._1).foreach { (number, code) =>
      if previous.exists(number > _ + 1) then source += "// … lines omitted …"
      source += code
      previous = Some(number)
    }
    val initial = source.mkString("\n")
    var code = initial
    val steps = blocks.flatMap { block =>
      val before = block.before.mkString("\n")
      val after = block.after.mkString("\n")
      val start = if before.nonEmpty then code.indexOf(before) else code.length
      if start < 0 then None
      else
        val issue = issues.find(issue =>
          issue.line >= block.start && issue.line < block.start + math.max(
            1,
            block.before.length
          )
        )
        code = code.take(start) + after + code.drop(start + before.length)
        Some(
          ujson.Obj(
            "rule" -> issue.fold("Rewrite")(_.name),
            "description" -> issue.fold("")(_.message),
            "start" -> start,
            "end" -> (start + before.length),
            "line" -> block.start,
            "column" -> 1,
            "before" -> before,
            "after" -> after,
            "code" -> code
          )
        )
    }
    ujson.Obj(
      "schemaVersion" -> 1,
      "file" -> path,
      "limit" -> steps.length,
      "truncated" -> false,
      "initial" -> initial,
      "steps" -> ujson.Arr(steps*)
    )

  private def sameFile(left: String, right: String): Boolean =
    val leftParts = Paths.get(left).iterator.asScala.map(_.toString).toSeq
    val rightParts = Paths.get(right).iterator.asScala.map(_.toString).toSeq
    val (shorter, longer) =
      if leftParts.length <= rightParts.length then (leftParts, rightParts)
      else (rightParts, leftParts)
    longer.takeRight(shorter.length) == shorter

  private def locateIssue(code: String, issue: LintIssue): Option[(Int, Int)] =
    val needle = issue.code.trim
    var offset = 0
    val matches = mutable.ArrayBuffer.empty[(Int, Int, Int)]
    code
      .split("(?<=\\n)", -1)
      .toSeq
      .filterNot(line => line.isEmpty && offset == code.length)
      .zipWithIndex
      .foreach { (line, index) =>
        val content = line.stripSuffix("\n").stripSuffix("\r")
        if content.trim == needle then
          val start =
            offset + content.length - content.dropWhile(_.isWhitespace).length
          matches += ((
            math.abs(index + 1 - issue.line),
            start,
            offset + content.length
          ))
        offset += line.length
      }
    matches.sortBy(_._1).headOption.map(value => (value._2, value._3))

  private def addScalafmtStep(history: ujson.Obj, original: String): Unit =
    val formatted = history("initial").str
    if original != formatted then
      var start = 0
      while start < math.min(original.length, formatted.length) && original(
          start
        ) == formatted(start)
      do start += 1
      var suffix = 0
      while suffix < original.length - start && suffix < formatted.length - start &&
        original(original.length - suffix - 1) == formatted(
          formatted.length - suffix - 1
        )
      do suffix += 1
      val originalEnd = original.length - suffix
      val formattedEnd = formatted.length - suffix
      val lineStart = original.lastIndexOf('\n', math.max(0, start - 1)) + 1
      history.value("initial") = ujson.Str(original)
      history("steps").arr.insert(
        0,
        ujson.Obj(
          "rule" -> "Scalafmt",
          "description" -> "Format the submitted Scala source",
          "start" -> start,
          "end" -> originalEnd,
          "line" -> (original.take(start).count(_ == '\n') + 1),
          "column" -> (start - lineStart + 1),
          "before" -> original.slice(start, originalEnd),
          "after" -> formatted.slice(start, formattedEnd),
          "code" -> formatted
        )
      )

  def buildReport(
      report: Path,
      data: Path,
      lookup: Map[String, ujson.Obj],
      mockup: String,
      includeScalafmt: Boolean = false,
      publicId: Option[String] = None,
      websiteDir: Path = Paths.get("feedback_website")
  ): ReportPage =
    val issues = parseLint(read(report))
    val run =
      report.getParent.getFileName.toString.stripPrefix("grading_reports_")
    val submission = report.getFileName.toString.stripSuffix(".lint.txt")
    val root = report.getParent.getParent
    val historyDir = root.resolve("grading_histories_" + run)
    val histories = list(historyDir)
      .filter(path =>
        val name = path.getFileName.toString
        name.startsWith(submission + "-") && name.endsWith(".history.json")
      )
      .map { path =>
        val history = ujson.read(read(path)) match
          case objectValue: ujson.Obj => objectValue
          case _                      =>
            throw IllegalArgumentException("Invalid rewrite history: " + path)
        if history.value.get("schemaVersion").forall(_.num != 1) ||
          !history.value.get("initial").exists(_.isInstanceOf[ujson.Str]) ||
          !history.value.get("steps").exists(_.isInstanceOf[ujson.Arr])
        then throw IllegalArgumentException("Invalid rewrite history: " + path)
        if includeScalafmt then
          val original = root
            .resolve("grading_originals_" + run)
            .resolve(
              submission + "-" + Paths.get(history("file").str).getFileName
            )
          if !Files.isRegularFile(original) then
            throw IllegalArgumentException(
              "Missing original source for Scalafmt step: " + original
            )
          addScalafmtStep(history, read(original))
        history
      }
      .to(mutable.ArrayBuffer)

    val paths = issues.map(_.path).distinct.sorted
    histories.foreach { history =>
      paths.find(path => sameFile(path, history("file").str)).foreach { path =>
        history.value("file") = ujson.Str(path)
      }
    }
    val diffDir = root.resolve("grading_diffs_" + run)
    paths.foreach { path =>
      if !histories.exists(history => sameFile(path, history("file").str)) then
        val basename = Paths.get(path).getFileName.toString
        val diff = diffDir.resolve(submission + "-" + basename + ".diff")
        val (originalLines, blocks) =
          if Files.isRegularFile(diff) && paths.count(p =>
              Paths.get(p).getFileName.toString == basename
            ) == 1
          then parseDiff(read(diff))
          else (Map.empty[Int, String], Seq.empty[DiffBlock])
        val lines = mutable.TreeMap.from(originalLines)
        val fileIssues = issues.filter(_.path == path)
        fileIssues.foreach(issue =>
          lines.getOrElseUpdate(issue.line, issue.code)
        )
        histories += fallbackHistory(path, lines.toMap, blocks, fileIssues)
    }

    val feedbackItems = mutable.LinkedHashMap.empty[String, FeedbackItem]
    var rewriteNumber = 0
    histories.foreach { history =>
      history("steps").arr.foreach { rawStep =>
        val step = rawStep match
          case objectValue: ujson.Obj => objectValue
          case _ => throw IllegalArgumentException("Invalid rewrite step")
        val id = "rewrite-" + rewriteNumber
        rewriteNumber += 1
        val description = step.value.get("description").fold("")(_.str)
        val values = Map[String, Any](
          "file" -> history("file").str,
          "line" -> step("line").num.toInt,
          "column" -> step("column").num.toInt,
          "rule_name" -> step("rule").str,
          "rule_message" -> description,
          "rewrite" -> step("after").str,
          "original_code" -> step("before").str
        )
        val rendered =
          fillTemplate(resolveRule(step("rule").str, lookup), values)
        step.value("kind") = ujson.Str("rewrite")
        step.value("id") = ujson.Str(id)
        Seq("title", "explanation", "message", "location").foreach { field =>
          step.value(field) = ujson.Str(rendered(field))
        }
        feedbackItems(id) = FeedbackItem(
          step("rule").str,
          history("file").str,
          step("line").num.toInt,
          step("column").num.toInt
        )
      }
    }

    val seen = mutable.Set.empty[(String, String)]
    var observationNumber = 0
    issues.foreach { issue =>
      histories
        .find(history => sameFile(issue.path, history("file").str))
        .foreach { history =>
          val key = (issue.name, history("file").str)
          val alreadyRewritten =
            history("steps").arr.exists(_("rule").str == issue.name)
          if !seen(key) && !alreadyRewritten then
            seen += key
            val steps = history("steps").arr
            val finalCode =
              steps.lastOption.fold(history("initial").str)(_("code").str)
            locateIssue(finalCode, issue).foreach { (start, end) =>
              val id = "observation-" + observationNumber
              observationNumber += 1
              val rendered = fillTemplate(
                resolveRule(issue.name, lookup),
                Map(
                  "file" -> issue.path,
                  "line" -> issue.line,
                  "column" -> issue.column,
                  "rule_name" -> issue.name,
                  "rule_message" -> issue.message,
                  "rewrite" -> "",
                  "original_code" -> issue.code
                )
              )
              steps += ujson.Obj(
                "kind" -> "observation",
                "id" -> id,
                "rule" -> issue.name,
                "description" -> issue.message,
                "title" -> rendered("title"),
                "explanation" -> rendered("explanation"),
                "message" -> rendered("message"),
                "location" -> rendered("location"),
                "start" -> start,
                "end" -> end,
                "line" -> issue.line,
                "column" -> issue.column,
                "before" -> issue.code.trim,
                "after" -> issue.code.trim,
                "code" -> finalCode
              )
              feedbackItems(id) =
                FeedbackItem(issue.name, issue.path, issue.line, issue.column)
            }
        }
    }

    val visibleHistories = histories.filter(_("steps").arr.nonEmpty).toSeq
    val timelines = visibleHistories.zipWithIndex.map { (history, index) =>
      val steps = history("steps").arr
      val warning =
        if history.value.get("truncated").exists(_.bool) then
          s"<p class=\"limit-warning\">The rewrite limit of ${history("limit").num.toInt} was reached; more patterns may still match.</p>"
        else ""
      s"""<section class="timeline" data-history-index="$index"><div class="timeline-heading"><div><span class="eyebrow">Feedback timeline</span><h2>${escape(
          Paths.get(history("file").str).getFileName.toString
        )}</h2><p class="location">${escape(
          history("file").str
        )}</p></div><span class="step-count" data-step-count></span></div><div class="workspace"><div class="code-panel"><div class="code-toolbar"><span data-version-label>Original</span><span class="context-tools"><span data-context-label></span><span>Scala · Prism</span></span></div><div class="code-view"><table class="code-table" role="presentation"><tbody data-code></tbody></table></div></div><aside class="change-card" data-change-card></aside></div><nav class="timeline-nav" aria-label="Feedback navigation"><button type="button" class="secondary" data-back>← Back</button><div class="progress-wrap"><progress data-progress max="${steps.length}" value="0"></progress><span data-progress-label></span></div><button type="button" data-forward>Next feedback →</button></nav><section class="history" data-history hidden><div class="history-title"><span class="eyebrow">History</span><h3>Changes and observations</h3></div><ol data-history-list></ol></section>$warning</section>"""
    }.mkString
    val empty =
      if visibleHistories.nonEmpty then ""
      else
        "<section class=\"empty-state\"><h2>No feedback</h2><p>No configured pattern matched this submission.</p></section>"
    val title = submission + " · " + run
    val main =
      s"""<main class="shell"><header class="page-header"><div><span class="eyebrow">Lorikeet feedback</span><h1>See your code evolve</h1><p>Step through rewrites, then review observations on the final code.</p></div><div class="run-label">${escape(
          submission
        )}<span>${escape(run)}</span></div></header>$timelines$empty</main>"""

    val relative = data.relativize(report).iterator.asScala.mkString("/")
    val filename = publicId.fold(
      sanitize(run + "-" + submission) + "-" + sha256(relative).take(
        8
      ) + ".html"
    )(_.split('/').last + ".html")
    val metadataIssues = mutable.LinkedHashMap.empty[String, ujson.Value]
    feedbackItems.foreach { (id, item) =>
      metadataIssues(id) = ujson.Obj(
        "id" -> sha256(
          s"$id\u0000${item.rule}\u0000${item.file}\u0000${item.line}\u0000${item.column}"
        ).take(20),
        "rule" -> item.rule,
        "file" -> item.file,
        "line" -> item.line,
        "column" -> item.column
      )
    }
    val metadata = ujson.Obj(
      "report_id" -> publicId.getOrElse(filename.stripSuffix(".html")),
      "submission" -> submission,
      "run" -> run,
      "issues" -> ujson.Obj.from(metadataIssues)
    )
    if publicId.nonEmpty then
      metadata.value("event_endpoint") = ujson.Str("/api/events")
    val timelineData = jsonScript(
      ujson.Obj("histories" -> ujson.Arr(visibleHistories*)).render()
    )
    val logData = jsonScript(metadata.render())
    val prism = Seq(
      "prism-core.min.js",
      "prism-clike.min.js",
      "prism-java.min.js",
      "prism-scala.min.js"
    ).map(name => read(websiteDir.resolve("vendor").resolve(name))).mkString
    val scripts =
      s"<script>$prism</script><script type=\"application/json\" id=\"timeline-data\">$timelineData</script><script type=\"application/json\" id=\"feedback-log-data\">$logData</script><script>${read(websiteDir.resolve("tracking.js"))}</script>"
    val page = renderDocument(mockup, title, main, interactive = true)
      .replace("</body>", scripts + "</body>")
    val counts =
      feedbackItems.values.groupMapReduce(_.rule)(_ => 1)(_ + _).toMap
    ReportPage(filename, title, page, feedbackItems.size, counts)

  private def buildOverview(
      entries: Seq[OverviewEntry],
      mockup: String
  ): String =
    val rows = entries.map { entry =>
      s"<tr><td><a href=\"${escape(entry.filename)}\">${escape(entry.submission)}</a></td><td>${escape(entry.run)}</td><td>${entry.counts.values.sum}</td><td>${escape(entry.counts.keys.toSeq.sorted.mkString(", ") match
          case ""    => "None"
          case value => value)}</td></tr>"
    }.mkString
    val main =
      s"""<main class="shell"><div class="header"><h1>Overview</h1></div><section class="panel" style="overflow:auto"><table><thead><tr><th>Submission</th><th>Run</th><th>Issues</th><th>Rules</th></tr></thead><tbody>$rows</tbody></table></section></main>"""
    renderDocument(mockup, "Overview", main, interactive = false).replace(
      "</head>",
      "<style>table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:14px 16px;border-bottom:1px solid var(--line)}th{color:var(--muted);font-weight:600}tbody tr:last-child td{border-bottom:0}a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}</style></head>"
    )

  def reportFiles(config: GeneratorConfig): Seq[Path] =
    val reports = Using.resource(Files.walk(config.data)) { paths =>
      paths.iterator.asScala
        .filter(Files.isRegularFile(_))
        .filter(_.getFileName.toString.endsWith(".lint.txt"))
        .filter(_.getParent.getFileName.toString.startsWith("grading_reports_"))
        .filter(path =>
          config.run.forall(run =>
            path.getParent.getFileName.toString == "grading_reports_" + run
          )
        )
        .toSeq
        .sortBy(_.toString)
    }
    if reports.isEmpty then
      val suffix = config.run.fold("")(run => " for run " + run)
      throw IllegalArgumentException(
        s"No grading reports found under ${config.data}$suffix"
      )
    reports

  def generate(config: GeneratorConfig): Unit =
    val templates = loadTemplates(config)
    val mockup = read(config.mockup)
    val reports = reportFiles(config)
    config.publishLab match
      case Some(lab) => publish(config, lab, templates.lookup, mockup, reports)
      case None      =>
        val pages = reports.map(report =>
          report -> buildReport(
            report,
            config.data,
            templates.lookup,
            mockup,
            config.includeScalafmt,
            websiteDir = config.mockup.toAbsolutePath.normalize().getParent
          )
        )
        Files.createDirectories(config.output)
        val entries = pages.map { (report, page) =>
          write(config.output.resolve(page.filename), page.page)
          OverviewEntry(
            page.filename,
            report.getFileName.toString.stripSuffix(".lint.txt"),
            report.getParent.getFileName.toString
              .stripPrefix("grading_reports_"),
            page.counts
          )
        }
        write(
          config.output.resolve("overview.html"),
          buildOverview(entries, mockup)
        )
        Seq("index.html", "rules.html").foreach(name =>
          Files.deleteIfExists(config.output.resolve(name))
        )
        println(
          s"Generated ${pages.length} reports (${pages.map(_._2.count).sum} issues). Open ${config.output.resolve("overview.html")}"
        )

  private def publish(
      config: GeneratorConfig,
      lab: String,
      lookup: Map[String, ujson.Obj],
      mockup: String,
      reports: Seq[Path]
  ): Unit =
    if !LabName.matches(lab) then
      throw IllegalArgumentException(
        "Publish lab must use 1-64 letters, numbers, underscores, or hyphens"
      )
    if config.run.isEmpty then
      throw IllegalArgumentException("--publish-lab requires --run")
    val secret = deploymentSecret(
      config.deploymentRoot.resolve("private/hmac.key")
    )
    val publicRoot = config.deploymentRoot.resolve("public")
    val manifests = config.deploymentRoot.resolve("private/links")
    Files.createDirectories(publicRoot)
    Files.createDirectories(manifests)
    val temporary = Files.createTempDirectory(publicRoot, "." + lab + "-")
    try
      val rows = reports
        .map { report =>
          val submission = report.getFileName.toString.stripSuffix(".lint.txt")
          val token = hmac(secret, lab + "\u0000" + submission)
          val publicId = lab + "/" + token
          val page = buildReport(
            report,
            config.data,
            lookup,
            mockup,
            config.includeScalafmt,
            Some(publicId),
            config.mockup.toAbsolutePath.normalize().getParent
          )
          write(temporary.resolve(page.filename), page.page)
          submission -> ("/r/" + publicId)
        }
        .sortBy(_._1)

      val target = publicRoot.resolve(lab)
      val previous = publicRoot.resolve("." + lab + "-previous")
      if Files.exists(previous) && !Files.exists(target) then
        Files.move(previous, target)
      else if Files.exists(previous) then deleteRecursively(previous)
      if Files.exists(target) then Files.move(target, previous)
      try Files.move(temporary, target)
      catch
        case error: Throwable =>
          if Files.exists(previous) && !Files.exists(target) then
            Files.move(previous, target)
          throw error
      if Files.exists(previous) then deleteRecursively(previous)

      val csv = ("submission,url" +: rows.map((submission, url) =>
        csvField(submission) + "," + csvField(url)
      )).mkString("\n") + "\n"
      val manifest = manifests.resolve(lab + ".csv")
      atomicWrite(manifest, csv.getBytes(UTF_8))
      println(
        s"Published ${rows.length} reports for $lab. Private links: $manifest"
      )
    finally if Files.exists(temporary) then deleteRecursively(temporary)

  private def deploymentSecret(path: Path): Array[Byte] =
    Files.createDirectories(path.getParent)
    val secret =
      if Files.exists(path) then Files.readAllBytes(path)
      else
        val bytes = Array.ofDim[Byte](32)
        SecureRandom().nextBytes(bytes)
        try Files.write(path, bytes, StandardOpenOption.CREATE_NEW)
        catch
          case _: java.nio.file.FileAlreadyExistsException =>
            return deploymentSecret(path)
        bytes
    try
      Files.setPosixFilePermissions(
        path,
        PosixFilePermissions.fromString("rw-------")
      )
    catch case _: UnsupportedOperationException => ()
    if secret.length < 32 then
      throw IllegalArgumentException(
        "Deployment secret must contain at least 32 bytes: " + path
      )
    secret

  private def renderDocument(
      mockup: String,
      title: String,
      main: String,
      interactive: Boolean
  ): String =
    val mainPattern = "(?s)<main\\b[^>]*>.*?</main>".r
    if mainPattern.findAllMatchIn(mockup).length != 1 then
      throw IllegalArgumentException("The mockup must contain a main element")
    val withMain = mainPattern.replaceFirstIn(
      mockup,
      java.util.regex.Matcher.quoteReplacement(main)
    )
    val withTitle = "(?s)<title>.*?</title>".r.replaceFirstIn(
      withMain,
      java.util.regex.Matcher
        .quoteReplacement("<title>" + escape(title) + "</title>")
    )
    if interactive then withTitle
    else "(?s)<script\\b[^>]*>.*?</script>".r.replaceAllIn(withTitle, "")

  private def copyObject(value: ujson.Obj): ujson.Obj =
    ujson.read(value.render()).obj

  private def list(directory: Path): Seq[Path] =
    if !Files.isDirectory(directory) then Seq.empty
    else
      Using.resource(Files.list(directory))(
        _.iterator.asScala.toSeq.sortBy(_.toString)
      )

  private def read(path: Path): String = Files.readString(path, UTF_8)

  private def write(path: Path, content: String): Unit =
    Files.createDirectories(path.getParent)
    Files.writeString(path, content, UTF_8)

  private def atomicWrite(path: Path, content: Array[Byte]): Unit =
    Files.createDirectories(path.getParent)
    val temporary =
      Files.createTempFile(path.getParent, "." + path.getFileName, ".tmp")
    try
      Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel =>
          channel.write(ByteBuffer.wrap(content))
          channel.force(true)
      }
      if Files.exists(path) then
        try
          Files.setPosixFilePermissions(
            temporary,
            Files.getPosixFilePermissions(path)
          )
        catch case _: UnsupportedOperationException => ()
      try
        Files.move(
          temporary,
          path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      catch
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)) { paths =>
        paths.iterator.asScala.toSeq
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists(_))
      }

  private def sanitize(value: String): String =
    value.replaceAll("[^A-Za-z0-9._-]", "-")

  private def escape(value: Any): String = value.toString.flatMap {
    case '&'  => "&amp;"
    case '<'  => "&lt;"
    case '>'  => "&gt;"
    case '"'  => "&quot;"
    case '\'' => "&#x27;"
    case char => char.toString
  }

  private def jsonScript(value: String): String = value.replace("<", "\\u003c")

  private def sha256(value: String): String = hex(
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))
  )

  private def sha256(value: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(value))

  private def hmac(secret: Array[Byte], value: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    hex(mac.doFinal(value.getBytes(UTF_8)))

  private def hex(bytes: Array[Byte]): String =
    bytes.map("%02x".format(_)).mkString

  private def csvField(value: Any): String =
    val string = value.toString
    if string.exists(char =>
        char == ',' || char == '"' || char == '\n' || char == '\r'
      )
    then "\"" + string.replace("\"", "\"\"") + "\""
    else string

  private val LogLock = Object()
  private val LogFields = Set(
    "event_id",
    "session_id",
    "report_id",
    "submission",
    "run",
    "event_type",
    "timestamp",
    "issue",
    "rating"
  )
  private val IssueFields = Set("id", "rule", "file", "line", "column")
  private val SummaryFields = Seq(
    "session_id",
    "report_id",
    "submission",
    "run",
    "issue_id",
    "rule",
    "file",
    "line",
    "column",
    "viewed",
    "first_viewed_at",
    "last_viewed_at",
    "view_count",
    "rated",
    "rating",
    "rated_at"
  )

  private def logText(value: ujson.Value, field: String, limit: Int): String =
    value match
      case ujson.Str(text)
          if text.trim.nonEmpty && text.length <= limit && !text
            .exists(char => char < ' ' || char == 127) =>
        text
      case _ =>
        throw IllegalArgumentException(
          s"$field must be nonempty text of at most $limit characters"
        )

  private def logTimestamp(
      value: ujson.Value,
      field: String = "timestamp"
  ): String =
    val text = logText(value, field, 40)
    try Instant.parse(text).toString
    catch
      case _: Exception =>
        throw IllegalArgumentException(
          s"$field must be an ISO 8601 UTC timestamp"
        )

  private def parseLogEvent(value: ujson.Value): LogEvent =
    val objectValue = value.obj
    if objectValue.keySet != LogFields then
      throw IllegalArgumentException(
        "Event fields do not match the feedback log format"
      )
    val eventId = logText(objectValue("event_id"), "event_id", 36)
    try UUID.fromString(eventId)
    catch
      case _: IllegalArgumentException =>
        throw IllegalArgumentException("event_id must be a UUID")
    val sessionId = logText(objectValue("session_id"), "session_id", 200)
    val reportId = logText(objectValue("report_id"), "report_id", 300)
    val submission = logText(objectValue("submission"), "submission", 500)
    val run = logText(objectValue("run"), "run", 300)
    val eventType = logText(objectValue("event_type"), "event_type", 32)
    if !Set("issue_loaded", "feedback_view", "feedback_rating")(eventType) then
      throw IllegalArgumentException("Unknown feedback event type")
    val rating = objectValue("rating") match
      case ujson.Null                                             => None
      case ujson.Str(value) if Set("positive", "negative")(value) => Some(value)
      case _ => throw IllegalArgumentException("Invalid feedback rating")
    if eventType == "feedback_rating" && rating.isEmpty then
      throw IllegalArgumentException(
        "A rating event needs a positive or negative rating"
      )
    if eventType != "feedback_rating" && rating.nonEmpty then
      throw IllegalArgumentException("Only rating events may include a rating")
    val issueValue = objectValue("issue").obj
    if issueValue.keySet != IssueFields then
      throw IllegalArgumentException(
        "Issue fields do not match the feedback log format"
      )
    def positiveInt(field: String): Int = issueValue(field) match
      case ujson.Num(number)
          if number.isValidInt && number >= 1 && number <= 10_000_000 =>
        number.toInt
      case _ =>
        throw IllegalArgumentException(
          s"issue.$field must be a positive integer"
        )
    val issue = LogIssue(
      logText(issueValue("id"), "issue.id", 200),
      logText(issueValue("rule"), "issue.rule", 500),
      logText(issueValue("file"), "issue.file", 2000),
      positiveInt("line"),
      positiveInt("column")
    )
    LogEvent(
      eventId,
      sessionId,
      reportId,
      submission,
      run,
      eventType,
      logTimestamp(objectValue("timestamp")),
      issue,
      rating
    )

  private def logEventJson(event: LogEvent): ujson.Obj =
    val value = ujson.Obj(
      "event_id" -> event.eventId,
      "session_id" -> event.sessionId,
      "report_id" -> event.reportId,
      "submission" -> event.submission,
      "run" -> event.run,
      "event_type" -> event.eventType,
      "timestamp" -> event.timestamp,
      "issue" -> ujson.Obj(
        "id" -> event.issue.id,
        "rule" -> event.issue.rule,
        "file" -> event.issue.file,
        "line" -> event.issue.line,
        "column" -> event.issue.column
      ),
      "rating" -> event.rating.fold[ujson.Value](ujson.Null)(ujson.Str(_))
    )
    event.receivedAt.foreach(timestamp =>
      value.value("server_received_at") = ujson.Str(timestamp)
    )
    value

  private def readLogEvents(path: Path): Seq[LogEvent] =
    if !Files.exists(path) then return Seq.empty
    val bytes = Files.readAllBytes(path)
    val text = String(bytes, UTF_8)
    val complete = text.endsWith("\n")
    val lines = text.split("\n", -1).toSeq.dropRight(if complete then 1 else 0)
    val events = mutable.ArrayBuffer.empty[LogEvent]
    lines.zipWithIndex.foreach { (line, index) =>
      try
        val stored = ujson.read(line)
        val received =
          logTimestamp(stored("server_received_at"), "server_received_at")
        val event = ujson.Obj.from(
          stored.obj.filterNot((field, _) => field == "server_received_at")
        )
        events += parseLogEvent(event).copy(receivedAt = Some(received))
      catch
        case NonFatal(error) if index == lines.length - 1 && !complete =>
          val newline = bytes.lastIndexOf('\n'.toByte)
          Using.resource(FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel =>
              channel.truncate(newline + 1L)
              channel.force(true)
          }
        case NonFatal(error) =>
          throw IllegalArgumentException(
            s"Invalid stored feedback event on line ${index + 1}: ${error.getMessage}",
            error
          )
    }
    if events.nonEmpty && bytes.nonEmpty && !complete && events.length == lines.length
    then
      Using.resource(FileChannel.open(path, StandardOpenOption.APPEND)) {
        channel =>
          channel.write(ByteBuffer.wrap("\n".getBytes(UTF_8)))
          channel.force(true)
      }
    events.toSeq

  private def summaryRows(events: Seq[LogEvent]): Seq[SummaryRow] =
    val rows = mutable.Map.empty[(String, String, String), SummaryRow]
    events.foreach { event =>
      val key = (event.sessionId, event.reportId, event.issue.id)
      val row = rows.getOrElseUpdate(
        key,
        SummaryRow(
          event.sessionId,
          event.reportId,
          event.submission,
          event.run,
          event.issue
        )
      )
      if row.submission != event.submission || row.run != event.run || row.issue != event.issue
      then
        throw IllegalArgumentException(
          "Issue metadata changed within this session and report"
        )
      event.eventType match
        case "feedback_view" =>
          row.viewed = true
          row.viewCount += 1
          row.firstViewedAt =
            if row.firstViewedAt.isEmpty || event.timestamp < row.firstViewedAt
            then event.timestamp
            else row.firstViewedAt
          row.lastViewedAt =
            if event.timestamp > row.lastViewedAt then event.timestamp
            else row.lastViewedAt
        case "feedback_rating" =>
          val order =
            (event.timestamp, event.receivedAt.getOrElse(""), event.eventId)
          if row.ratingOrder.forall(previous =>
              summon[Ordering[(String, String, String)]].lt(previous, order)
            )
          then
            row.ratingOrder = Some(order)
            row.rated = true
            row.rating = event.rating.get
            row.ratedAt = event.timestamp
        case _ => ()
    }
    rows.toSeq.sortBy(_._1).map(_._2)

  private def writeSummary(path: Path, events: Seq[LogEvent]): Unit =
    val lines = summaryRows(events).map { row =>
      Seq(
        row.sessionId,
        row.reportId,
        row.submission,
        row.run,
        row.issue.id,
        row.issue.rule,
        row.issue.file,
        row.issue.line,
        row.issue.column,
        row.viewed,
        row.firstViewedAt,
        row.lastViewedAt,
        row.viewCount,
        row.rated,
        row.rating,
        row.ratedAt
      ).map(csvField).mkString(",")
    }
    atomicWrite(
      path,
      ((SummaryFields.mkString(",") +: lines).mkString("\n") + "\n")
        .getBytes(UTF_8)
    )

  private def appendEvent(logDirectory: Path, payload: ujson.Value): ujson.Obj =
    val event = parseLogEvent(payload)
    Files.createDirectories(logDirectory)
    LogLock.synchronized {
      val eventPath = logDirectory.resolve("feedback_events.jsonl")
      val events = readLogEvents(eventPath).to(mutable.ArrayBuffer)
      val normalized = logEventJson(event).render()
      var duplicate = false
      events.foreach { stored =>
        if stored.eventId == event.eventId then
          if logEventJson(stored.copy(receivedAt = None)).render() != normalized
          then
            throw IllegalArgumentException(
              "event_id was already used for a different event"
            )
          duplicate = true
      }
      if !duplicate then
        val stored = event.copy(receivedAt = Some(Instant.now().toString))
        val bytes = (logEventJson(stored).render() + "\n").getBytes(UTF_8)
        Using.resource(
          FileChannel.open(
            eventPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND
          )
        ) { channel =>
          channel.write(ByteBuffer.wrap(bytes))
          channel.force(true)
        }
        events += stored
      writeSummary(logDirectory.resolve("feedback_summary.csv"), events.toSeq)
    }
    ujson.Obj("saved" -> true, "event_id" -> event.eventId)

  def startReviewServer(config: GeneratorConfig): RunningReviewServer =
    val editorToken = randomToken()
    val logToken = randomToken()
    val executor = Executors.newFixedThreadPool(8)
    val server =
      HttpServer.create(new InetSocketAddress("127.0.0.1", config.port), 0)
    server.setExecutor(executor)
    val port = server.getAddress.getPort
    server.createContext(
      "/",
      exchange => handleReview(exchange, config, port, editorToken, logToken)
    )
    server.start()
    RunningReviewServer(server, executor)

  private def handleReview(
      exchange: HttpExchange,
      config: GeneratorConfig,
      port: Int,
      editorToken: String,
      logToken: String
  ): Unit =
    try
      if !trusted(exchange, port) then
        sendJson(exchange, 403, "Invalid local host")
      else
        (exchange.getRequestMethod, exchange.getRequestURI.getPath) match
          case ("GET", "/api/log-token") =>
            send(
              exchange,
              200,
              ujson.Obj("token" -> logToken).render(),
              "application/json; charset=utf-8"
            )
          case ("GET", "/api/logs/events") =>
            serveLog(
              exchange,
              config.logs.resolve("feedback_events.jsonl"),
              "text/plain; charset=utf-8"
            )
          case ("GET", "/api/logs/summary") =>
            serveLog(
              exchange,
              config.logs.resolve("feedback_summary.csv"),
              "text/csv; charset=utf-8"
            )
          case ("GET", "/api/templates") =>
            val raw = Files.readAllBytes(config.rules)
            val templates = loadTemplates(config)
            send(
              exchange,
              200,
              ujson
                .Obj(
                  "templates" -> ujson.Obj.from(templates.templates),
                  "revision" -> sha256(raw)
                )
                .render(),
              "application/json; charset=utf-8"
            )
          case ("GET", "/") | ("GET", "/editor") =>
            serveEditor(exchange, config, editorToken)
          case ("GET", path)
              if path.matches(raw"/generated/[A-Za-z0-9._-]+\.html") =>
            val page = config.output.resolve(path.split('/').last)
            if Files.isRegularFile(page) then
              send(
                exchange,
                200,
                Files.readAllBytes(page),
                "text/html; charset=utf-8"
              )
            else sendJson(exchange, 404, "Not found")
          case ("POST", "/api/log-events") =>
            if !sameLocalOrigin(exchange) || exchange.getRequestHeaders
                .getFirst("X-Log-Token") != logToken
            then sendJson(exchange, 403, "Refresh the log connection")
            else
              val payload =
                ujson.read(String(readBody(exchange, 32_000), UTF_8))
              send(
                exchange,
                200,
                appendEvent(config.logs, payload).render(),
                "application/json; charset=utf-8"
              )
          case ("POST", "/api/templates") =>
            saveTemplates(exchange, config, editorToken)
          case _ => sendJson(exchange, 404, "Not found")
    catch
      case error: IllegalArgumentException =>
        sendJson(exchange, 400, error.getMessage)
      case NonFatal(error) =>
        System.err.println("Review server error: " + error.getMessage)
        sendJson(exchange, 500, error.getMessage)
    finally exchange.close()

  private def serveLog(
      exchange: HttpExchange,
      path: Path,
      contentType: String
  ): Unit =
    if Files.isRegularFile(path) then
      send(exchange, 200, Files.readAllBytes(path), contentType)
    else
      sendJson(
        exchange,
        404,
        "No feedback interactions have been recorded yet."
      )

  private def serveEditor(
      exchange: HttpExchange,
      config: GeneratorConfig,
      token: String
  ): Unit =
    val raw = Files.readAllBytes(config.rules)
    val templates = loadTemplates(config)
    val templateJson = ujson.Obj.from(templates.templates)
    val editorConfig = jsonScript(
      ujson
        .Obj(
          "token" -> token,
          "templates" -> templateJson,
          "revision" -> sha256(raw)
        )
        .render()
    )
    val rows = templates.templates.zipWithIndex.map {
      case ((name, template), index) =>
        s"<section><label for=\"description-$index\">${escape(name)}</label><textarea id=\"description-$index\" data-rule=\"${escape(name)}\">${escape(template("explanation").str)}</textarea></section>"
    }.mkString
    val page =
      read(config.mockup.getParent.resolve("rule_description_editor.html"))
        .replace("__RULE_ROWS__", rows)
        .replace("__EDITOR_CONFIG__", editorConfig)
    send(exchange, 200, page, "text/html; charset=utf-8")

  private def saveTemplates(
      exchange: HttpExchange,
      config: GeneratorConfig,
      editorToken: String
  ): Unit =
    if !sameLocalOrigin(exchange) || exchange.getRequestHeaders.getFirst(
        "X-Editor-Token"
      ) != editorToken
    then
      sendJson(exchange, 403, "Please open the editor from its local address")
    else
      val payload = ujson.read(String(readBody(exchange, 2_000_000), UTF_8)).obj
      val templates = validateTemplates(payload("templates"))
      val raw = Files.readAllBytes(config.rules)
      if payload("revision").str != sha256(raw) then
        sendJson(
          exchange,
          409,
          "The file was changed elsewhere. Copy your unsaved edits, then reload this page."
        )
      else
        val updated =
          (ujson.write(ujson.Obj.from(templates.templates), indent = 2) + "\n")
            .getBytes(UTF_8)
        val backup = config.rules.resolveSibling(
          config.rules.getFileName.toString + ".bak"
        )
        atomicWrite(backup, raw)
        atomicWrite(config.rules, updated)
        val revision = sha256(updated)
        try
          generate(config)
          send(
            exchange,
            200,
            ujson
              .Obj(
                "revision" -> revision,
                "message" -> "Saved. Feedback pages updated."
              )
              .render(),
            "application/json; charset=utf-8"
          )
        catch
          case NonFatal(error) =>
            send(
              exchange,
              200,
              ujson
                .Obj(
                  "revision" -> revision,
                  "message" -> ("Templates saved, but feedback generation failed: " + error.getMessage)
                )
                .render(),
              "application/json; charset=utf-8"
            )

  private def trusted(exchange: HttpExchange, port: Int): Boolean =
    Set(s"127.0.0.1:$port", s"localhost:$port").contains(
      exchange.getRequestHeaders.getFirst("Host")
    )

  private def sameLocalOrigin(exchange: HttpExchange): Boolean =
    Option(exchange.getRequestHeaders.getFirst("Origin")).forall(
      _ == "http://" + exchange.getRequestHeaders.getFirst("Host")
    )

  private def readBody(exchange: HttpExchange, maximum: Int): Array[Byte] =
    val bytes = exchange.getRequestBody.readNBytes(maximum + 1)
    if bytes.isEmpty || bytes.length > maximum then
      throw IllegalArgumentException("Invalid request size")
    bytes

  private def sendJson(
      exchange: HttpExchange,
      status: Int,
      error: String
  ): Unit =
    send(
      exchange,
      status,
      ujson.Obj("error" -> error).render(),
      "application/json; charset=utf-8"
    )

  private def send(
      exchange: HttpExchange,
      status: Int,
      body: String,
      contentType: String
  ): Unit = send(exchange, status, body.getBytes(UTF_8), contentType)

  private def send(
      exchange: HttpExchange,
      status: Int,
      body: Array[Byte],
      contentType: String
  ): Unit =
    exchange.getResponseHeaders.set("Content-Type", contentType)
    exchange.getResponseHeaders.set("Content-Length", body.length.toString)
    exchange.getResponseHeaders.set("Cache-Control", "no-store")
    exchange.getResponseHeaders.set("X-Content-Type-Options", "nosniff")
    exchange.sendResponseHeaders(status, body.length)
    exchange.getResponseBody.write(body)

  private def randomToken(): String =
    val bytes = Array.ofDim[Byte](32)
    SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

object GenerateFeedbackMain:
  private val Usage =
    """Usage: scala-cli run feedback_website/GenerateFeedback.scala -- [options]
    |  --data PATH              grading output root
    |  --rules PATH             feedback template JSON
    |  --mockup PATH            feedback HTML template
    |  --output PATH            local generated pages
    |  --logs PATH              local interaction logs
    |  --run TIMESTAMP          select one grading run
    |  --publish-lab NAME       publish opaque student URLs for one lab
    |  --deployment-root PATH   deployment output root
    |  --include-scalafmt       include formatting as rewrite zero
    |  --serve                  start the local review/editor server
    |  --port NUMBER            local review server port
    |""".stripMargin

  def parseArgs(arguments: Seq[String]): GeneratorConfig =
    val root = Paths.get(".").toAbsolutePath.normalize()
    val website = root.resolve("feedback_website")
    val grading = root.resolve("grading/output")
    var config = GeneratorConfig(
      data = grading,
      rules = website.resolve("rule_templates.json"),
      mockup = website.resolve("feedback_mockup.html"),
      output = grading.resolve("feedback"),
      logs = grading.resolve("feedback_logs"),
      run = None,
      publishLab = None,
      deploymentRoot = grading.resolve("deployment"),
      includeScalafmt = false,
      serve = false,
      port = 8765
    )
    var index = 0
    def argument(option: String): String =
      index += 1
      if index >= arguments.length then
        throw IllegalArgumentException("Missing value for " + option)
      arguments(index)
    while index < arguments.length do
      arguments(index) match
        case "--data" =>
          config = config.copy(data = Paths.get(argument("--data")))
        case "--rules" =>
          config = config.copy(rules = Paths.get(argument("--rules")))
        case "--mockup" =>
          config = config.copy(mockup = Paths.get(argument("--mockup")))
        case "--output" =>
          config = config.copy(output = Paths.get(argument("--output")))
        case "--logs" =>
          config = config.copy(logs = Paths.get(argument("--logs")))
        case "--run" => config = config.copy(run = Some(argument("--run")))
        case "--publish-lab" =>
          config = config.copy(publishLab = Some(argument("--publish-lab")))
        case "--deployment-root" =>
          config = config.copy(deploymentRoot =
            Paths.get(argument("--deployment-root"))
          )
        case "--include-scalafmt" =>
          config = config.copy(includeScalafmt = true)
        case "--serve" => config = config.copy(serve = true)
        case "--port"  => config = config.copy(port = argument("--port").toInt)
        case "--help" | "-h" =>
          println(Usage)
          sys.exit(0)
        case option =>
          throw IllegalArgumentException("Unknown option: " + option)
      index += 1
    if config.publishLab.nonEmpty && config.serve then
      throw IllegalArgumentException(
        "--publish-lab cannot be combined with the local review server"
      )
    config

  def main(args: Array[String]): Unit =
    try
      val config = parseArgs(args.toSeq)
      GenerateFeedback.generate(config)
      if config.serve then
        val running = GenerateFeedback.startReviewServer(config)
        val stopped = CountDownLatch(1)
        Runtime.getRuntime.addShutdownHook(Thread(() =>
          running.close()
          stopped.countDown()
        ))
        println(s"Rule description editor: http://127.0.0.1:${running.port}/")
        stopped.await()
    catch
      case error: IllegalArgumentException =>
        System.err.println("error: " + error.getMessage)
        sys.exit(2)
