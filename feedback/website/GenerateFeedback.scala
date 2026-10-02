//> using scala 3.7.4
//> using dep com.lihaoyi::ujson:4.4.3
//> using test.dep org.scalameta::munit:1.3.6
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
final case class SubmissionResult(submission: String, run: String, status: String)
object GenerateFeedback:
  private val LabName = raw"[A-Za-z0-9][A-Za-z0-9_-]{0,63}".r

  def startReviewServer(config: GeneratorConfig): RunningReviewServer =
    FeedbackReview.startReviewServer(config)

  def buildReport(
      result: SubmissionResult,
      data: Path,
      template: String,
      publicId: Option[String] = None,
      websiteDir: Path = Paths.get("feedback/website")
  ): ReportPage =
    val submission = result.submission
    val run = result.run
    val filename = publicId.fold(
      sanitize(run + "-" + submission) + "-" +
        sha256(run + "/" + submission).take(8) + ".html"
    )(_.split('/').last + ".html")
    val header =
      s"""|<header class="page-header">
          |  <p>
          |    Hi! We, the people of the <a href="https://cs-214.epfl.ch/">CS-214</a> course staff and the <a href="https://systemf.epfl.ch/">SYSTEMF</a>, have been developing a new experimental way for giving you more personalized <b>Code Quality Feedback</b>. As usual, you can find code quality tips in the <a href="https://cs-214.epfl.ch/#debriefs">Debriefs</a>. This page offers some interactive suggestions, specifically for you! You can find your submitted code in the snippet below. (Yes, we do look at it!) You can use the buttons below to see your code transform!
          |  </p>
          |  <p>
          |    Small disclaimer: This is an experimental research project, so beware. There might be some mistakes (and code-style opinions ^^), don't take everything blindly. If you spot any mistakes, you can report them <a href="https://github.com/epfl-systemf/lorikeet/issues/new">here</a>.
          |  </p>
          |</header>""".stripMargin
    val failure = result.status match
      case "missing_files" => Some("We couldn't process your submission: a required file was missing.")
      case "compile_error" => Some("We couldn't process your submission: it did not compile.")
      case "rewrite_error" => Some("We couldn't process your submission: the rewritten code did not compile.")
      case "processing_error" => Some("We couldn't process your submission: a processing error occurred.")
      case "issues" | "success" => None
      case other => throw IllegalArgumentException("Unknown result status: " + other)
    if failure.nonEmpty then
      val main =
        s"""<main class="shell">$header<section class="empty-state"><h2>Feedback unavailable</h2><p>${escape(failure.get)}</p></section></main>"""
      return ReportPage(
        filename,
        submission,
        renderDocument(template, submission, main, interactive = false),
        0,
        Map.empty
      )

    val historyDir = data.resolve("grading_histories_" + run)
    val historyPath = historyDir.resolve(submission + ".history.json")
    if !Files.isRegularFile(historyPath) then
      throw IllegalArgumentException(
        s"Missing rewrite history for $submission in run $run"
      )
    val bundle = ujson.read(read(historyPath))
    if bundle("schemaVersion").num != 1 ||
      bundle("student").str != submission then
      throw IllegalArgumentException("Invalid student history: " + historyPath)
    val histories = bundle("files").arr.map { rawHistory =>
        val history = rawHistory match
          case objectValue: ujson.Obj => objectValue
          case _                      =>
            throw IllegalArgumentException("Invalid rewrite history: " + historyPath)
        if history.value.get("schemaVersion").forall(_.num != 1) ||
          !history.value.get("initial").exists(_.isInstanceOf[ujson.Str]) ||
          !history.value.get("steps").exists(_.isInstanceOf[ujson.Arr]) ||
          !history.value.get("lints").exists(_.isInstanceOf[ujson.Arr]) ||
          history.value.get("truncated").exists(_.bool)
        then throw IllegalArgumentException("Invalid rewrite history: " + historyPath)
        var code = history("initial").str
        history("steps").arr.foreach { step =>
          val start = step("start").num.toInt
          val end = step("end").num.toInt
          if start < 0 || end < start || end > code.length ||
            code.slice(start, end) != step("before").str ||
            step("code").str != code.take(start) + step("after").str + code.drop(end)
          then throw IllegalArgumentException("Invalid rewrite transition: " + historyPath)
          code = step("code").str
        }
        history.value("file") = ujson.Str(Paths.get(history("file").str).getFileName.toString)
        history
      }
      .to(mutable.ArrayBuffer)
    if histories.isEmpty then
      throw IllegalArgumentException(
        s"Missing rewrite history for $submission in run $run"
      )

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
        val item = FeedbackItem(
          step("rule").str,
          history("file").str,
          step("line").num.toInt,
          step("column").num.toInt
        )
        feedbackItems(id + "-highlight") = item.copy(rule = item.rule + " (highlight)")
        feedbackItems(id + "-result") = item.copy(rule = item.rule + " (rewritten code)")
      }
    }

    var observationNumber = 0
    histories.foreach { history =>
      val steps = history("steps").arr
      val finalCode = steps.lastOption.fold(history("initial").str)(_("code").str)
      val byRule = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[ujson.Value]]
      history("lints").arr.foreach { lint =>
        val start = lint("start").num.toInt
        val end = lint("end").num.toInt
        if start < 0 || end < start || end > finalCode.length then
          throw IllegalArgumentException("Invalid final lint in " + history("file").str)
        byRule.getOrElseUpdate(lint("rule").str, mutable.ArrayBuffer.empty) += lint
      }
      byRule.foreach { (rule, matches) =>
        val first = matches.head
        val id = "observation-" + observationNumber
        observationNumber += 1
        val file = history("file").str
        val line = first("line").num.toInt
        val column = first("column").num.toInt
        val start = first("start").num.toInt
        val end = first("end").num.toInt
        steps += ujson.Obj(
          "kind" -> "observation",
          "id" -> id,
          "rule" -> rule,
          "description" -> first("description").str,
          "title" -> rule,
          "explanation" -> first("description").str,
          "location" -> s"$file:$line:$column",
          "locations" -> ujson.Arr.from(matches.map { lint =>
            ujson.Obj(
              "start" -> lint("start"),
              "end" -> lint("end"),
              "line" -> lint("line"),
              "column" -> lint("column")
            )
          }),
          "start" -> start,
          "end" -> end,
          "line" -> line,
          "column" -> column,
          "before" -> finalCode.slice(start, end),
          "after" -> finalCode.slice(start, end),
          "code" -> finalCode
        )
        feedbackItems(id) = FeedbackItem(rule, file, line, column)
      }
    }

    val visibleHistories = histories.filter(_("steps").arr.nonEmpty).toSeq
    val timelines = visibleHistories.zipWithIndex.map { (history, index) =>
      val steps = history("steps").arr
      val warning =
        if history.value.get("truncated").exists(_.bool) then
          s"<p class=\"limit-warning\">The rewrite limit of ${history("limit").num.toInt} was reached; more patterns may still match.</p>"
        else ""
      s"""|<section class="timeline" data-history-index="$index">
          |  <div class="workspace">
          |    <div class="code-panel">
          |      <div class="code-toolbar">
          |        <span data-version-label>Original</span>
          |        <span class="context-tools">
          |          <span data-context-label></span>
          |          <span>Scala</span>
          |        </span>
          |      </div>
          |      <div class="code-view"><table class="code-table" role="presentation">
          |        <tbody data-code></tbody></table>
          |      </div>
          |    </div>
          |    <aside class="change-card" data-change-card></aside>
          |  </div>
          |  <nav class="timeline-nav" aria-label="Feedback navigation">
          |    <div class="progress-wrap">
          |      <ol class="step-list" data-step-dots aria-label="Feedback steps"></ol>
          |      <span data-progress-label></span>
          |    </div>
          |    <button type="button" class="secondary" aria-keyshortcuts="ArrowLeft" data-back>Back (←)</button>
          |    <div class="nav-action"><button type="button" aria-keyshortcuts="ArrowRight" data-forward>Next feedback (→)</button><div data-rating-slot hidden></div></div>
          |  </nav>
          |  <section class="history" data-history hidden><div class="history-title"><span class="eyebrow">History</span><h3>Changes and observations</h3></div><ol data-history-list></ol>
          |  </section>$warning
          |</section>""".stripMargin
    }.mkString
    val empty =
      if visibleHistories.nonEmpty then ""
      else
        "<section class=\"empty-state\"><h2>No feedback</h2><p>We didn't match any code-quality improvement patterns on your submission. Until next time.</p></section>"
    val title = submission
    val main =
      s"""<main class="shell">$header$timelines$empty</main>"""
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
    val counts = visibleHistories.flatMap(_("steps").arr).groupMapReduce(_("rule").str)(
      step => if step("kind").str == "observation" then step("locations").arr.size else 1
    )(_ + _).toMap
    ReportPage(filename, title, page, counts.values.sum, counts)

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

  def submissionResults(config: GeneratorConfig): Seq[SubmissionResult] =
    val manifests = list(config.data).filter { path =>
      val name = path.getFileName.toString
      name.startsWith("grading_results_") && name.endsWith(".json") &&
      config.run.forall(run => name == s"grading_results_$run.json")
    }
    if manifests.isEmpty then
      val suffix = config.run.fold("")(run => " for run " + run)
      throw IllegalArgumentException(
        s"No grading results found under ${config.data}$suffix"
      )
    manifests.flatMap { manifest =>
      val run = manifest.getFileName.toString
        .stripPrefix("grading_results_").stripSuffix(".json")
      ujson.read(read(manifest)).arr.map { row =>
        SubmissionResult(row("student").str, run, row("status").str)
      }
    }.sortBy(result => (result.run, result.submission))

  def generate(config: GeneratorConfig): Unit =
    val template = read(config.template)
    val results = submissionResults(config)
    config.publishLab match
      case Some(lab) => publish(config, lab, template, results)
      case None      =>
        val pages = results.map(result =>
          result -> buildReport(
            result,
            config.data,
            template,
            websiteDir = config.template.toAbsolutePath.normalize().getParent
          )
        )
        Files.createDirectories(config.output)
        val entries = pages.map { (result, page) =>
          write(config.output.resolve(page.filename), page.page)
          OverviewEntry(
            page.filename,
            result.submission,
            result.run,
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
      results: Seq[SubmissionResult]
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
      val rows = results
        .map { result =>
          val submission = result.submission
          val token = hmac(secret, lab + "\u0000" + submission)
          val publicId = lab + "/" + token
          val page = buildReport(
            result,
            config.data,
            template,
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
