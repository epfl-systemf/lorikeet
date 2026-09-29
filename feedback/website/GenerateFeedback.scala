//> using scala 3.7.4
//> using dep com.lihaoyi::ujson:4.4.3
//> using test.dep org.scalameta::munit:1.3.6
//> using file FeedbackParsing.scala
//> using file FeedbackFiles.scala
//> using file FeedbackReview.scala

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.security.{MessageDigest, SecureRandom}
import java.util.concurrent.CountDownLatch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import FeedbackFiles.{atomicWrite, csvField}

final case class GeneratorConfig(
    data: Path,
    template: Path,
    output: Path,
    logs: Path,
    run: Option[String],
    publishLab: Option[String],
    deploymentRoot: Path,
    includeScalafmt: Boolean,
    serve: Boolean,
    port: Int
)

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
object GenerateFeedback:
  private val LabName = raw"[A-Za-z0-9][A-Za-z0-9_-]{0,63}".r

  def parseLint(text: String): Seq[LintIssue] = FeedbackParsing.parseLint(text)
  def parseDiff(text: String): (Map[Int, String], Seq[DiffBlock]) =
    FeedbackParsing.parseDiff(text)
  def startReviewServer(config: GeneratorConfig): RunningReviewServer =
    FeedbackReview.startReviewServer(config)

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
      template: String,
      includeScalafmt: Boolean = false,
      publicId: Option[String] = None,
      websiteDir: Path = Paths.get("feedback/website")
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
        step.value("kind") = ujson.Str("rewrite")
        step.value("id") = ujson.Str(id)
        step.value("title") = step("rule")
        step.value("explanation") = ujson.Str(description)
        step.value("location") = ujson.Str(
          s"${history("file").str}:${step("line").num.toInt}:${step("column").num.toInt}"
        )
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
              steps += ujson.Obj(
                "kind" -> "observation",
                "id" -> id,
                "rule" -> issue.name,
                "description" -> issue.message,
                "title" -> issue.name,
                "explanation" -> issue.message,
                "location" -> s"${issue.path}:${issue.line}:${issue.column}",
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
    val page = renderDocument(template, title, main, interactive = true)
      .replace("</body>", scripts + "</body>")
    val counts =
      feedbackItems.values.groupMapReduce(_.rule)(_ => 1)(_ + _).toMap
    ReportPage(filename, title, page, feedbackItems.size, counts)

  private def buildOverview(
      entries: Seq[OverviewEntry],
      template: String
  ): String =
    val rows = entries.map { entry =>
      s"<tr><td><a href=\"${escape(entry.filename)}\">${escape(entry.submission)}</a></td><td>${escape(entry.run)}</td><td>${entry.counts.values.sum}</td><td>${escape(entry.counts.keys.toSeq.sorted.mkString(", ") match
          case ""    => "None"
          case value => value)}</td></tr>"
    }.mkString
    val main =
      s"""<main class="shell"><div class="header"><h1>Overview</h1></div><section class="panel" style="overflow:auto"><table><thead><tr><th>Submission</th><th>Run</th><th>Issues</th><th>Rules</th></tr></thead><tbody>$rows</tbody></table></section></main>"""
    renderDocument(template, "Overview", main, interactive = false).replace(
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
    val template = read(config.template)
    val reports = reportFiles(config)
    config.publishLab match
      case Some(lab) => publish(config, lab, template, reports)
      case None      =>
        val pages = reports.map(report =>
          report -> buildReport(
            report,
            config.data,
            template,
            config.includeScalafmt,
            websiteDir = config.template.toAbsolutePath.normalize().getParent
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
          buildOverview(entries, template)
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
      template: String,
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
            template,
            config.includeScalafmt,
            Some(publicId),
            config.template.toAbsolutePath.normalize().getParent
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
      template: String,
      title: String,
      main: String,
      interactive: Boolean
  ): String =
    val mainPattern = "(?s)<main\\b[^>]*>.*?</main>".r
    if mainPattern.findAllMatchIn(template).length != 1 then
      throw IllegalArgumentException("The template must contain a main element")
    val withMain = mainPattern.replaceFirstIn(
      template,
      java.util.regex.Matcher.quoteReplacement(main)
    )
    val withTitle = "(?s)<title>.*?</title>".r.replaceFirstIn(
      withMain,
      java.util.regex.Matcher
        .quoteReplacement("<title>" + escape(title) + "</title>")
    )
    if interactive then withTitle
    else "(?s)<script\\b[^>]*>.*?</script>".r.replaceAllIn(withTitle, "")

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

object GenerateFeedbackMain:
  private val Usage =
    """Usage: scala-cli run feedback/website/GenerateFeedback.scala -- [options]
    |  --data PATH              feedback automation output root
    |  --template PATH          feedback HTML template
    |  --output PATH            local generated pages
    |  --logs PATH              local interaction logs
    |  --run TIMESTAMP          select one grading run
    |  --publish-lab NAME       publish opaque student URLs for one lab
    |  --deployment-root PATH   deployment output root
    |  --include-scalafmt       include formatting as rewrite zero
    |  --serve                  start the local review server
    |  --port NUMBER            local review server port
    |""".stripMargin

  def parseArgs(arguments: Seq[String]): GeneratorConfig =
    val root = Paths.get(".").toAbsolutePath.normalize()
    val website = root.resolve("feedback/website")
    val grading = root.resolve("feedback/feedback_automation/output")
    var config = GeneratorConfig(
      data = grading,
      template = website.resolve("feedback_template.html"),
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
        case "--template" =>
          config = config.copy(template = Paths.get(argument("--template")))
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
        println(s"Review site: http://127.0.0.1:${running.port}/")
        stopped.await()
    catch
      case error: IllegalArgumentException =>
        System.err.println("error: " + error.getMessage)
        sys.exit(2)
