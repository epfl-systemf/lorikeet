import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.SecureRandom
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.Executors
import scala.collection.mutable
import scala.util.Using
import scala.util.control.NonFatal
import FeedbackFiles.{atomicWrite, csvField}

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

object FeedbackReview:
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
    if !Set("issue_loaded", "feedback_view", "feedback_rating", "geek_mode_enabled", "geek_mode_disabled")(eventType) then
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
    events.filterNot(event => Set("geek_mode_enabled", "geek_mode_disabled")(event.eventType)).foreach { event =>
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
    val logToken = randomToken()
    val executor = Executors.newFixedThreadPool(8)
    val server =
      HttpServer.create(new InetSocketAddress("127.0.0.1", config.port), 0)
    server.setExecutor(executor)
    val port = server.getAddress.getPort
    server.createContext(
      "/",
      exchange => handleReview(exchange, config, port, logToken)
    )
    server.start()
    RunningReviewServer(server, executor)

  private def handleReview(
      exchange: HttpExchange,
      config: GeneratorConfig,
      port: Int,
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
          case ("GET", "/") =>
            exchange.getResponseHeaders.set("Location", "/generated/overview.html")
            send(exchange, 302, "", "text/plain; charset=utf-8")
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
