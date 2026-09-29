import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID

class GenerateFeedbackTest extends munit.FunSuite:
  test("recovers legacy diffs with trimmed blank context") {
    val (original, blocks) = GenerateFeedback.parseDiff(
      "--- before\n+++ after\n@@ -1,2 +1,2 @@\n-old\n+new"
    )
    assertEquals(original, Map(1 -> "old", 2 -> ""))
    assertEquals(blocks, Seq(DiffBlock(1, Seq("old"), Seq("new"))))
    interceptMessage[IllegalArgumentException]("Truncated diff hunk") {
      GenerateFeedback.parseDiff(
        "--- before\n+++ after\n@@ -1,2 +1,2 @@\n-old\n+new\n"
      )
    }
  }

  test("generates, publishes, serves, and logs feedback") {
    val root = Files.createTempDirectory("feedback-generator-test")
    val reports = Files.createDirectories(root.resolve("grading_reports_demo"))
    val histories =
      Files.createDirectories(root.resolve("grading_histories_demo"))
    val originals =
      Files.createDirectories(root.resolve("grading_originals_demo"))
    val report = reports.resolve("student-0.lint.txt")
    Files.writeString(
      report,
      """[Rewrite]
        |rewrite it (1 occurrences)
        |
        |src/Sample.scala:1:1
        |if ready then true else false
        |^
        |
        |[Var Usage]
        |avoid mutation (2 occurrences)
        |
        |src/Sample.scala:2:1
        |var found = false
        |^
        |
        |src/Sample.scala:3:1
        |var current = false
        |^
        |""".stripMargin,
      UTF_8
    )
    Files.writeString(
      histories.resolve("student-0-a.history.json"),
      ujson
        .Obj(
          "schemaVersion" -> 1,
          "file" -> "src/Sample.scala",
          "limit" -> 10,
          "truncated" -> false,
          "initial" -> "if ready then true else false\nvar found = false\nvar current = false",
          "steps" -> ujson.Arr(
            ujson.Obj(
              "rule" -> "Rewrite",
              "description" -> "rewrite it",
              "start" -> 0,
              "end" -> 29,
              "line" -> 1,
              "column" -> 1,
              "before" -> "if ready then true else false",
              "after" -> "ready",
              "code" -> "ready\nvar found = false\nvar current = false"
            )
          )
        )
        .render(indent = 2),
      UTF_8
    )
    Files.writeString(
      originals.resolve("student-0-Sample.scala"),
      "if  ready then true else false\nvar found=false\nvar current=false",
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
      includeScalafmt = true,
      serve = false,
      port = 0
    )

    GenerateFeedback.generate(config)
    val page = Files
      .list(output)
      .filter(_.toString.endsWith(".html"))
      .toArray
      .map(_.asInstanceOf[Path])
      .find(_.getFileName.toString != "overview.html")
      .get
    val html = Files.readString(page)
    val payload = ujson.read(
      "(?s)<script type=\"application/json\" id=\"timeline-data\">(.*?)</script>".r
        .findFirstMatchIn(html)
        .get
        .group(1)
    )
    val steps = payload("histories")(0)("steps").arr
    assertEquals(
      steps.map(_("kind").str).toSeq,
      Seq("rewrite", "rewrite", "observation")
    )
    assertEquals(steps.count(_("rule").str == "Var Usage"), 1)
    assertEquals(steps(1)("explanation").str, "rewrite it")
    assertEquals(steps(2)("explanation").str, "avoid mutation")
    assert(html.contains("codeView.scrollTop=previousTop;"))
    assert(html.contains("data-expand-${direction}"))

    val publishConfig = config.copy(
      publishLab = Some("find-2026"),
      output = root.resolve("unused")
    )
    GenerateFeedback.generate(publishConfig)
    val manifest = root.resolve("deployment/private/links/find-2026.csv")
    val link = Files.readAllLines(manifest, UTF_8).get(1).split(",", 2)(1)
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
      val events = get("/api/logs/events")
      assertEquals(events.statusCode(), 200, events.body())
      val summary = get("/api/logs/summary")
      assertEquals(summary.statusCode(), 200)
      assert(summary.body().contains("student-0"))
    finally running.close()
  }
