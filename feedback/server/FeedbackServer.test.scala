import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import scala.util.Using

class FeedbackServerTest extends munit.FunSuite:
  test("serves only opaque reports and records events") {
    val root = Files.createTempDirectory("feedback-server-test")
    val public = root.resolve("public")
    val token = "a" * 64
    Files.createDirectories(public.resolve("find"))
    Files.writeString(
      public.resolve("find").resolve(s"$token.html"),
      "<h1>feedback</h1>"
    )
    val database = root.resolve("feedback.sqlite")
    val running =
      FeedbackServer.start(FeedbackConfig(public, database, port = 0))
    try
      val client = HttpClient.newHttpClient()
      val base = s"http://127.0.0.1:${running.port}"
      def get(path: String) = client.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )

      assertEquals(get(s"/r/find/$token").statusCode(), 200)
      assertEquals(get("/generated/overview.html").statusCode(), 404)
      assertEquals(get("/api/templates").statusCode(), 404)
      assertEquals(get("/api/logs/events").statusCode(), 404)

      val event = ujson.Obj(
        "event_id" -> UUID.randomUUID().toString,
        "session_id" -> UUID.randomUUID().toString,
        "report_id" -> s"find/$token",
        "submission" -> "student",
        "run" -> "demo",
        "event_type" -> "feedback_view",
        "timestamp" -> Instant.now().toString,
        "issue" -> ujson.Obj(
          "id" -> "issue-1",
          "rule" -> "Example",
          "file" -> "find.scala",
          "line" -> 1,
          "column" -> 1
        ),
        "rating" -> ujson.Null
      )
      val request = HttpRequest
        .newBuilder(URI.create(base + "/api/events"))
        .header("Content-Type", "application/json")
        .header("Origin", base)
        .POST(HttpRequest.BodyPublishers.ofString(event.render()))
        .build()
      val response = client.send(
        request,
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(response.statusCode(), 200)
      assertEquals(
        client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(),
        200
      )
      val futureEvent = event.copy()
      futureEvent("event_id") = UUID.randomUUID().toString
      futureEvent("event_type") = "future_signal"
      val futureRequest = HttpRequest
        .newBuilder(URI.create(base + "/api/events"))
        .header("Content-Type", "application/json")
        .header("Origin", base)
        .POST(HttpRequest.BodyPublishers.ofString(futureEvent.render()))
        .build()
      assertEquals(
        client.send(futureRequest, HttpResponse.BodyHandlers.ofString()).statusCode(),
        200
      )
      futureEvent("event_id") = UUID.randomUUID().toString
      futureEvent("event_type") = "future-signal"
      val invalidFutureRequest = HttpRequest
        .newBuilder(URI.create(base + "/api/events"))
        .header("Content-Type", "application/json")
        .header("Origin", base)
        .POST(HttpRequest.BodyPublishers.ofString(futureEvent.render()))
        .build()
      assertEquals(
        client.send(invalidFutureRequest, HttpResponse.BodyHandlers.ofString()).statusCode(),
        400
      )
      val malformed = HttpRequest
        .newBuilder(URI.create(base + "/api/events"))
        .header("Content-Type", "application/json")
        .header("Origin", base)
        .POST(HttpRequest.BodyPublishers.ofString("{"))
        .build()
      assertEquals(
        client
          .send(malformed, HttpResponse.BodyHandlers.ofString())
          .statusCode(),
        400
      )
      val crossOrigin = HttpRequest
        .newBuilder(URI.create(base + "/api/events"))
        .header("Content-Type", "application/json")
        .header("Origin", "https://example.com")
        .POST(HttpRequest.BodyPublishers.ofString(event.render()))
        .build()
      assertEquals(
        client
          .send(crossOrigin, HttpResponse.BodyHandlers.ofString())
          .statusCode(),
        403
      )

      val count =
        Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$database")) {
          connection =>
            Using.resource(connection.createStatement()) { statement =>
              val result = statement.executeQuery("SELECT count(*) FROM events")
              result.next()
              result.getInt(1)
            }
        }
      assertEquals(count, 2)
    finally running.close()
  }
