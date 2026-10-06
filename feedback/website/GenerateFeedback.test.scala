import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

class GenerateFeedbackTest extends munit.FunSuite:
  test("generates, publishes, serves, and logs feedback") {
    val root = Files.createTempDirectory("feedback-generator-test")
    val histories =
      Files.createDirectories(root.resolve("grading_histories_demo"))
    val original = (1 to 40).map(i => s"val line$i = $i").mkString("\n")
    val before = (10 to 30).map(i => s"val line$i = $i").mkString("\n")
    val after = (10 to 30).map(i => s"val line$i = ${i + 1}").mkString("\n")
    val rewritten = original.replace(before, after)
    val start = original.indexOf(before)
    val firstFile = ujson.Obj(
      "schemaVersion" -> 1,
      "file" -> root.resolve("device/private/Sample.scala").toString,
      "limit" -> 10,
      "truncated" -> false,
      "initial" -> original,
      "steps" -> ujson.Arr(ujson.Obj(
        "rule" -> "Rewrite",
        "description" -> "rewrite it",
        "pattern" -> "val `?name` = `?value`",
        "rewrite" -> "val `?name` = `?value` + 1",
        "start" -> start,
        "end" -> (start + before.length),
        "line" -> 10,
        "column" -> 1,
        "before" -> before,
        "after" -> after,
        "code" -> rewritten
      )),
      "lints" -> ujson.Arr(
        ujson.Obj(
          "rule" -> "Var Usage",
          "description" -> "avoid mutation",
          "pattern" -> "var `?name` = `?value`",
          "start" -> 0,
          "end" -> 3,
          "line" -> 1,
          "column" -> 1,
          "code" -> "val line1 = 1"
        ),
        ujson.Obj(
          "rule" -> "Var Usage",
          "description" -> "avoid mutation",
          "pattern" -> "var `?name` = `?value`",
          "start" -> rewritten.indexOf("val line40"),
          "end" -> (rewritten.indexOf("val line40") + 3),
          "line" -> 40,
          "column" -> 1,
          "code" -> "val line40 = 40"
        )
      )
    )
    val secondFile = ujson.Obj(
      "schemaVersion" -> 1,
      "file" -> "src/Other.scala",
      "limit" -> 10,
      "truncated" -> false,
      "initial" -> "val answer = 0",
      "steps" -> ujson.Arr(ujson.Obj(
        "rule" -> "Second Rewrite",
        "description" -> "replace zero",
        "pattern" -> "0",
        "start" -> 13,
        "end" -> 14,
        "line" -> 1,
        "column" -> 14,
        "before" -> "0",
        "after" -> "1",
        "code" -> "val answer = 1"
      )),
      "lints" -> ujson.Arr(ujson.Obj(
        "rule" -> "Var Usage",
        "description" -> "avoid mutation",
        "pattern" -> "var `?name` = `?value`",
        "start" -> 0,
        "end" -> 3,
        "line" -> 1,
        "column" -> 1,
        "code" -> "val answer = 1"
      ))
    )
    Files.writeString(
      histories.resolve("student-0.history.json"),
      ujson.Obj(
        "schemaVersion" -> 1,
        "student" -> "student-0",
        "files" -> ujson.Arr(firstFile, secondFile)
      ).render(indent = 2),
      UTF_8
    )
    Files.writeString(
      root.resolve("grading_results_demo.json"),
      ujson.Arr(
        ujson.Obj("student" -> "student-0", "status" -> "issues"),
        ujson.Obj("student" -> "student-1", "status" -> "success"),
        ujson.Obj("student" -> "student-2", "status" -> "missing_files"),
        ujson.Obj("student" -> "student-3", "status" -> "compile_error"),
        ujson.Obj("student" -> "student-4", "status" -> "rewrite_error"),
        ujson.Obj("student" -> "student-5", "status" -> "processing_error")
      ).render(),
      UTF_8
    )
    Files.writeString(
      histories.resolve("student-1.history.json"),
      ujson.Obj(
        "schemaVersion" -> 1,
        "student" -> "student-1",
        "files" -> ujson.Arr(ujson.Obj(
        "schemaVersion" -> 1,
        "file" -> "src/NoMatches.scala",
        "limit" -> 10,
        "truncated" -> false,
        "initial" -> "object NoMatches",
        "steps" -> ujson.Arr(),
        "lints" -> ujson.Arr()
        ))
      ).render(),
      UTF_8
    )

    val repository = Paths.get(".").toAbsolutePath.normalize()
    val website = repository.resolve("feedback/website")
    val output = root.resolve("feedback")
    val config = GeneratorConfig(
      data = root,
      template = website.resolve("feedback_template.html"),
      output = output,
      logs = root.resolve("logs"),
      run = Some("demo"),
      publishLab = None,
      deploymentRoot = root.resolve("deployment"),
      serve = false,
      port = 0
    )

    GenerateFeedback.generate(config)
    val html = Files.readString(
      Files.list(output).toArray.map(_.asInstanceOf[Path])
        .find(_.getFileName.toString.contains("student-0")).get
    )
    val payload = ujson.read(
      "(?s)<script type=\"application/json\" id=\"timeline-data\">(.*?)</script>".r
        .findFirstMatchIn(html)
        .get
        .group(1)
    )
    val steps = payload("histories")(0)("steps").arr
    assertEquals(payload("histories").arr.size, 2)
    assertEquals(payload("histories")(0)("file").str, "Sample.scala")
    assertEquals(payload("histories")(1)("file").str, "Other.scala")
    assertEquals(steps(0)("location").str, "Sample.scala:10:1")
    assertEquals(steps(1)("location").str, "Sample.scala:1:1")
    assert(!html.contains(root.resolve("device/private").toString))
    assert(!html.contains("src/Other.scala"))
    assertEquals(payload("histories")(1)("steps")(0)("rule").str, "Second Rewrite")
    assertEquals(payload("histories")(1)("steps")(0)("id").str, "rewrite-1")
    assertEquals(payload("histories")(1)("steps")(1)("id").str, "observation-1")
    assertEquals(
      steps.map(_("kind").str).toSeq,
      Seq("rewrite", "observation")
    )
    assertEquals(steps.count(_("rule").str == "Var Usage"), 1)
    assertEquals(steps(1)("locations").arr.size, 2)
    assertEquals(steps(1)("locations")(1)("line").num.toInt, 40)
    assertEquals(steps(0)("explanation").str, "rewrite it")
    assertEquals(steps(0)("pattern").str, "val `?name` = `?value`")
    assertEquals(steps(0)("rewrite").str, "val `?name` = `?value` + 1")
    assertEquals(steps(1)("explanation").str, "avoid mutation")
    assertEquals(steps(0)("code").str, rewritten)
    assert(steps(0)("before").str.linesIterator.size > 5)
    assert(steps(0)("after").str.contains("val line30 = 31"))
    assert(html.contains("val line40 = 40"))
    assert(!html.contains("lines omitted"))
    assert(html.contains("codeView.scrollTop=previousTop;"))
    assert(html.contains("data-expand-${direction}"))
    assert(html.contains("let current=0,preview=false"))
    assert(html.contains("data-step-dots"))
    assert(html.contains("data-rating-slot"))
    assert(html.contains("data-geek-toggle"))
    assert(html.contains("data-geek-drawer"))
    assert(!html.contains("data-geek-close"))
    assert(html.contains("data-geek-content"))
    assert(html.contains("class=\"geek-drawer-body\""))
    assert(html.contains("class=\"geek-drawer-intro\""))
    assert(html.contains("Our tool matches your code against the PATTERN and replaces it with the REWRITE."))
    assert(html.contains("class=\"geek-drawer\""))
    assert(html.contains("aria-hidden=\"true\" inert"))
    assert(html.contains("Geek mode (G)"))
    assert(html.contains("aria-keyshortcuts=\"G\""))
    assert(html.contains("history.pushState"))
    assert(html.contains("window.history.back()"))
    assert(html.contains("window.addEventListener(\"popstate"))
    assert(html.contains("resetContext();render(true);announceView();"))
    assert(html.contains("document.addEventListener('timelinechange'"))
    assert(html.contains("document.documentElement.classList.toggle(\"geek-mode\",enabled)"))
    assert(html.contains("event.key===\"Escape\"&&geekMode"))
    assert(html.contains("event.key.toLowerCase()===\"g\""))
    assert(html.contains("geekmodechange"))
    assert(html.contains("geek_mode_enabled"))
    assert(html.contains("geekStep=step||null;"))
    assert(html.contains("renderGeek(step);"))
    assert(html.contains("grid-template-rows:auto minmax(0,1fr) auto"))
    assert(html.contains("text-decoration-style:dashed"))
    assert(html.contains("<aside class=\"disclaimer\">"))
    assert(html.contains("const needsRating=!!step&&!preview&&!hasRating(feedbackId());"))
    assert(html.contains("flex:1 0 48px"))
    assert(html.contains("scrollbar-gutter:stable"))
    val logPayload = ujson.read(
      "(?s)<script type=\"application/json\" id=\"feedback-log-data\">(.*?)</script>".r
        .findFirstMatchIn(html).get.group(1)
    )
    assert(Set("rewrite-0-highlight", "rewrite-0-result", "observation-0")
      .subsetOf(logPayload("issues").obj.keySet.toSet))
    assertEquals(logPayload("issues")("geek-mode")("rule").str, "Geek mode")
    assertEquals(logPayload("issues")("rewrite-0-highlight")("rule").str, "Rewrite (highlight)")
    assertEquals(logPayload("issues")("rewrite-0-result")("rule").str, "Rewrite (improved code)")
    assert(!html.contains("HIGHLIGHT_MS"))
    assert(html.contains("--accent:var(--color-rouge)"))
    assert(html.contains("width:min(1480px"))
    def localPage(student: String): String =
      Files.readString(Files.list(output).toArray.map(_.asInstanceOf[Path])
        .find(_.getFileName.toString.contains(student)).get)
    assert(localPage("student-1").contains("We didn't match any code-quality improvement patterns"))
    assert(localPage("student-2").contains("a required file was missing"))
    assert(localPage("student-3").contains("it did not compile"))
    assert(localPage("student-4").contains("the improved code did not compile"))
    assert(localPage("student-5").contains("a processing error occurred"))

    val publishConfig = config.copy(
      publishLab = Some("find-2026"),
      output = root.resolve("unused")
    )
    GenerateFeedback.generate(publishConfig)
    val manifest = root.resolve("deployment/private/links/find-2026.csv")
    assertEquals(Files.readAllLines(manifest, UTF_8).size(), 7)
    val link = Files.readAllLines(manifest, UTF_8).get(1).split(",", 2)(1)
    assert(link.startsWith("/r/find-2026/"))
    assert(!link.contains("student-0"))
    val published = root
      .resolve("deployment/public/find-2026")
      .resolve(link.split('/').last + ".html")
    assert(Files.isRegularFile(published))
    assert(!Files.exists(root.resolve("deployment/public/overview.html")))
    assert(
      Files.readString(published).contains("\"event_endpoint\":\"/api/events\"")
    )

    val otherLab =
      Files.createDirectories(root.resolve("deployment/public/boids-2026"))
    Files.createFile(otherLab.resolve("existing.html"))
    GenerateFeedback.generate(publishConfig)
    assertEquals(
      Files.readAllLines(manifest, UTF_8).get(1).split(",", 2)(1),
      link
    )
    assert(Files.isRegularFile(otherLab.resolve("existing.html")))

    val running = GenerateFeedback.startReviewServer(config)
    try
      val client = HttpClient.newHttpClient()
      val base = s"http://127.0.0.1:${running.port}"
      def get(path: String) = client.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(get("/").statusCode(), 302)
      assertEquals(get("/editor").statusCode(), 404)
      assertEquals(get("/api/templates").statusCode(), 404)
      assertEquals(get("/generated/overview.html").statusCode(), 200)
      val token = ujson.read(get("/api/log-token").body())("token").str
      val event = ujson.Obj(
        "event_id" -> UUID.randomUUID().toString,
        "session_id" -> UUID.randomUUID().toString,
        "report_id" -> "demo-student-0",
        "submission" -> "student-0",
        "run" -> "demo",
        "event_type" -> "feedback_view",
        "timestamp" -> Instant.now().toString,
        "issue" -> ujson.Obj(
          "id" -> "rewrite-0",
          "rule" -> "Rewrite",
          "file" -> "src/Sample.scala",
          "line" -> 1,
          "column" -> 1
        ),
        "rating" -> ujson.Null
      )
      val request = HttpRequest
        .newBuilder(URI.create(base + "/api/log-events"))
        .header("Content-Type", "application/json")
        .header("Origin", base)
        .header("X-Log-Token", token)
        .POST(HttpRequest.BodyPublishers.ofString(event.render()))
        .build()
      val duplicate = client.send(request, HttpResponse.BodyHandlers.ofString())
      assertEquals(duplicate.statusCode(), 200, duplicate.body())
      val replay = client.send(request, HttpResponse.BodyHandlers.ofString())
      assertEquals(replay.statusCode(), 200, replay.body())
      val geekEvent = event.copy()
      geekEvent("event_id") = UUID.randomUUID().toString
      geekEvent("event_type") = "geek_mode_enabled"
      geekEvent("issue") = ujson.Obj(
        "id" -> "geek-mode",
        "rule" -> "Geek mode",
        "file" -> "Feedback report",
        "line" -> 1,
        "column" -> 1
      )
      val geekRequest = HttpRequest
        .newBuilder(URI.create(base + "/api/log-events"))
        .header("Content-Type", "application/json")
        .header("Origin", base)
        .header("X-Log-Token", token)
        .POST(HttpRequest.BodyPublishers.ofString(geekEvent.render()))
        .build()
      assertEquals(client.send(geekRequest, HttpResponse.BodyHandlers.ofString()).statusCode(), 200)
      val events = get("/api/logs/events")
      assertEquals(events.statusCode(), 200, events.body())
      assert(events.body().contains("geek_mode_enabled"))
      val summary = get("/api/logs/summary")
      assertEquals(summary.statusCode(), 200)
      assert(summary.body().contains("student-0"))
      assert(!summary.body().contains("Geek mode"))
    finally running.close()
  }
