package api
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.generic.auto._
import io.circe.{Codec, Decoder, Encoder}
import java.util.UUID

case class RunRequest(
    rule: String,
    projectPaths: List[String]
) derives Codec

enum RunResult derives Encoder, Decoder:
  case Success
  case Failure

case class ProjectResult(
    path: String,
    result: RunResult,
    diff: Option[String] = None,
    report: Option[String] = None
) derives Codec

enum JobStatus derives Encoder, Decoder:
  case PENDING, RUNNING, COMPLETED, FAILED

case class Job(
    id: String = UUID.randomUUID().toString,
    request: RunRequest,
    status: JobStatus,
    results: Map[String, ProjectResult] // project path -> result
) derives Codec

object Endpoints {
  // Submit a refactoring job
  val runRefactor = endpoint.post
    .in("api" / "refactor")
    .in(jsonBody[RunRequest])
    .out(jsonBody[Job])
    .description("Submit a rule and batch of projects for execution")

  // Get the status of a job
  val getStatus = endpoint.get
    .in("api" / "jobs" / path[String]("jobId"))
    .out(jsonBody[Job])
    .errorOut(stringBody)

  val all = List(runRefactor, getStatus)
}
