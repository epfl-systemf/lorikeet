//> using scala 3.7.4
//> using dep org.xerial:sqlite-jdbc:3.53.4.0
//> using dep com.lihaoyi::ujson:4.4.3
//> using test.dep org.scalameta::munit:1.3.6

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, DriverManager}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors}
import scala.util.Using
import scala.util.control.NonFatal

final case class FeedbackConfig(
    publicDir: Path,
    database: Path,
    host: String = "127.0.0.1",
    port: Int = 8766
)

final case class FeedbackEvent(
    eventId: String,
    sessionId: String,
    reportId: String,
    eventType: String,
    timestamp: String,
    issueId: String,
    rule: String,
    file: String,
    line: Int,
    column: Int,
    rating: Option[String]
)

final class RunningFeedbackServer(
    val server: HttpServer,
    executor: java.util.concurrent.ExecutorService
) extends AutoCloseable:
  def port: Int = server.getAddress.getPort

  override def close(): Unit =
    server.stop(1)
    executor.shutdown()

object FeedbackServer:
  private val MaxBody = 32_000
  private val ReportId = raw"([A-Za-z0-9][A-Za-z0-9_-]{0,63})/([0-9a-f]{64})".r
  private val ReportRoute =
    raw"/r/([A-Za-z0-9][A-Za-z0-9_-]{0,63})/([0-9a-f]{64})".r

  def start(config: FeedbackConfig): RunningFeedbackServer =
    val publicDir = config.publicDir.toAbsolutePath.normalize()
    val database = config.database.toAbsolutePath.normalize()
    if !Files.isDirectory(publicDir) then
      throw IllegalArgumentException(
        s"Public report directory does not exist: $publicDir"
      )
    Option(database.getParent).foreach(Files.createDirectories(_))
    initializeDatabase(database)

    val executor = Executors.newFixedThreadPool(16)
    val server =
      HttpServer.create(new InetSocketAddress(config.host, config.port), 0)
    server.setExecutor(executor)
    server.createContext("/", exchange => handle(exchange, publicDir, database))
    server.start()
    RunningFeedbackServer(server, executor)

  private def handle(
      exchange: HttpExchange,
      publicDir: Path,
      database: Path
  ): Unit =
    try
      (exchange.getRequestMethod, exchange.getRequestURI.getPath) match
        case ("GET", "/healthz") =>
          send(exchange, 200, "ok\n", "text/plain; charset=utf-8")
        case ("GET", ReportRoute(lab, token)) =>
          val report =
            publicDir.resolve(lab).resolve(s"$token.html").normalize()
          if report.startsWith(publicDir) && Files.isRegularFile(report) then
            send(
              exchange,
              200,
              Files.readAllBytes(report),
              "text/html; charset=utf-8"
            )
          else sendJson(exchange, 404, "Report not found")
        case ("POST", "/api/events") =>
          receiveEvent(exchange, publicDir, database)
        case _ => sendJson(exchange, 404, "Not found")
    catch
      case error: IllegalArgumentException =>
        sendJson(exchange, 400, error.getMessage)
      case NonFatal(error) =>
        System.err.println(s"Request failed: ${error.getMessage}")
        sendJson(exchange, 500, "Internal server error")
    finally exchange.close()

  private def receiveEvent(
      exchange: HttpExchange,
      publicDir: Path,
      database: Path
  ): Unit =
    if !sameOrigin(exchange) then
      sendJson(exchange, 403, "Cross-origin requests are not allowed")
    else if !Option(exchange.getRequestHeaders.getFirst("Content-Type"))
        .exists(_.toLowerCase.startsWith("application/json"))
    then sendJson(exchange, 415, "Expected application/json")
    else
      val bytes = exchange.getRequestBody.readNBytes(MaxBody + 1)
      if bytes.isEmpty || bytes.length > MaxBody then
        sendJson(exchange, 400, "Invalid event size")
      else
        val event = parseEvent(new String(bytes, StandardCharsets.UTF_8))
        if reportPath(publicDir, event.reportId).forall(path =>
            !Files.isRegularFile(path)
          )
        then sendJson(exchange, 404, "Report not found")
        else
          insertEvent(database, event)
          send(
            exchange,
            200,
            ujson.Obj("saved" -> true, "event_id" -> event.eventId).render(),
            "application/json; charset=utf-8"
          )

  private def sameOrigin(exchange: HttpExchange): Boolean =
    Option(exchange.getRequestHeaders.getFirst("Origin")).forall { origin =>
      try
        val uri = URI.create(origin)
        Option(uri.getAuthority).exists(
          _.equalsIgnoreCase(exchange.getRequestHeaders.getFirst("Host"))
        )
      catch case _: IllegalArgumentException => false
    }

  private def reportPath(publicDir: Path, reportId: String): Option[Path] =
    reportId match
      case ReportId(lab, token) =>
        val path = publicDir.resolve(lab).resolve(s"$token.html").normalize()
        Option.when(path.startsWith(publicDir))(path)
      case _ => None

  private def parseEvent(body: String): FeedbackEvent =
    try parseEventUnsafe(body)
    catch
      case error: IllegalArgumentException => throw error
      case NonFatal(_) => throw IllegalArgumentException("Invalid event")

  private def parseEventUnsafe(body: String): FeedbackEvent =
    val json = ujson.read(body).obj

    def text(
        objectValue: collection.Map[String, ujson.Value],
        name: String,
        max: Int
    ): String =
      objectValue.get(name) match
        case Some(ujson.Str(value))
            if value.nonEmpty && value.length <= max && !value.exists(
              _.isControl
            ) =>
          value
        case _ => throw IllegalArgumentException(s"Invalid $name")

    val eventId = text(json, "event_id", 36)
    val sessionId = text(json, "session_id", 200)
    try UUID.fromString(eventId)
    catch
      case _: IllegalArgumentException =>
        throw IllegalArgumentException("Invalid event_id")
    try UUID.fromString(sessionId)
    catch
      case _: IllegalArgumentException =>
        throw IllegalArgumentException("Invalid session_id")

    val reportId = text(json, "report_id", 160)
    if !ReportId.matches(reportId) then
      throw IllegalArgumentException("Invalid report_id")
    val eventType = text(json, "event_type", 32)
    if !Set("issue_loaded", "feedback_view", "feedback_rating").contains(
        eventType
      )
    then throw IllegalArgumentException("Invalid event_type")
    val timestamp = text(json, "timestamp", 40)
    try Instant.parse(timestamp)
    catch
      case _: Exception => throw IllegalArgumentException("Invalid timestamp")

    val issue = json
      .get("issue")
      .map(_.obj)
      .getOrElse(throw IllegalArgumentException("Invalid issue"))
    val line = integer(issue, "line")
    val column = integer(issue, "column")
    val rating = json.get("rating") match
      case Some(ujson.Str(value))
          if Set("positive", "negative").contains(value) =>
        Some(value)
      case Some(ujson.Null) | None => None
      case _ => throw IllegalArgumentException("Invalid rating")
    if eventType == "feedback_rating" && rating.isEmpty then
      throw IllegalArgumentException("Rating event requires a rating")
    if eventType != "feedback_rating" && rating.nonEmpty then
      throw IllegalArgumentException("Only rating events may include a rating")

    FeedbackEvent(
      eventId,
      sessionId,
      reportId,
      eventType,
      timestamp,
      text(issue, "id", 128),
      text(issue, "rule", 300),
      text(issue, "file", 1000),
      line,
      column,
      rating
    )

  private def integer(
      values: collection.Map[String, ujson.Value],
      name: String
  ): Int =
    values.get(name) match
      case Some(ujson.Num(value)) if value.isValidInt && value >= 0 =>
        value.toInt
      case _ => throw IllegalArgumentException(s"Invalid $name")

  private def connect(database: Path): Connection =
    val connection = DriverManager.getConnection(s"jdbc:sqlite:$database")
    Using.resource(connection.createStatement())(
      _.execute("PRAGMA busy_timeout = 5000")
    )
    connection

  private def initializeDatabase(database: Path): Unit =
    Class.forName("org.sqlite.JDBC")
    Using.resource(connect(database)) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        statement.execute("PRAGMA journal_mode = WAL")
        statement.execute(
          """CREATE TABLE IF NOT EXISTS events (
            |  event_id TEXT PRIMARY KEY,
            |  report_id TEXT NOT NULL,
            |  session_id TEXT NOT NULL,
            |  event_type TEXT NOT NULL,
            |  issue_id TEXT NOT NULL,
            |  rule TEXT NOT NULL,
            |  file TEXT NOT NULL,
            |  line INTEGER NOT NULL,
            |  column_number INTEGER NOT NULL,
            |  rating TEXT,
            |  client_timestamp TEXT NOT NULL,
            |  received_timestamp TEXT NOT NULL
            |)""".stripMargin
        )
        statement.execute(
          "CREATE INDEX IF NOT EXISTS events_report ON events(report_id)"
        )
      }
    }

  private def insertEvent(database: Path, event: FeedbackEvent): Unit =
    Using.resource(connect(database)) { connection =>
      Using.resource(
        connection.prepareStatement(
          """INSERT OR IGNORE INTO events (
          |  event_id, report_id, session_id, event_type, issue_id, rule, file,
          |  line, column_number, rating, client_timestamp, received_timestamp
          |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
        )
      ) { statement =>
        val values = Seq(
          event.eventId,
          event.reportId,
          event.sessionId,
          event.eventType,
          event.issueId,
          event.rule,
          event.file,
          event.line,
          event.column,
          event.rating.orNull,
          event.timestamp,
          Instant.now().toString
        )
        values.zipWithIndex.foreach((value, index) =>
          statement.setObject(index + 1, value)
        )
        statement.executeUpdate()
      }
    }

  private def sendJson(
      exchange: HttpExchange,
      status: Int,
      message: String
  ): Unit =
    send(
      exchange,
      status,
      ujson.Obj("error" -> message).render(),
      "application/json; charset=utf-8"
    )

  private def send(
      exchange: HttpExchange,
      status: Int,
      body: String,
      contentType: String
  ): Unit =
    send(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType)

  private def send(
      exchange: HttpExchange,
      status: Int,
      body: Array[Byte],
      contentType: String
  ): Unit =
    val headers = exchange.getResponseHeaders
    headers.set("Content-Type", contentType)
    headers.set("Cache-Control", "private, no-store")
    headers.set(
      "Content-Security-Policy",
      "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
        "connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"
    )
    headers.set("Referrer-Policy", "no-referrer")
    headers.set("X-Content-Type-Options", "nosniff")
    headers.set("X-Frame-Options", "DENY")
    exchange.sendResponseHeaders(status, body.length)
    exchange.getResponseBody.write(body)

object FeedbackServerMain:
  def main(args: Array[String]): Unit =
    val environment = sys.env
    val config = FeedbackConfig(
      publicDir = Paths.get(
        environment
          .getOrElse("FEEDBACK_PUBLIC_DIR", "grading/output/deployment/public")
      ),
      database = Paths.get(
        environment.getOrElse(
          "FEEDBACK_DB",
          "grading/output/deployment/state/feedback.sqlite"
        )
      ),
      host = environment.getOrElse("FEEDBACK_HOST", "127.0.0.1"),
      port = environment.get("FEEDBACK_PORT").fold(8766)(_.toInt)
    )
    val running = FeedbackServer.start(config)
    val stopped = CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(Thread(() =>
      running.close()
      stopped.countDown()
    ))
    println(
      s"Feedback server listening on http://${config.host}:${running.port}"
    )
    stopped.await()
