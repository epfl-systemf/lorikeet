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

enum RunResult:
  case SUCCESS
  case FAILURE

given Codec[RunResult] = Codec.from(
  Decoder.decodeString.emap {
    case "SUCCESS" => Right(RunResult.SUCCESS)
    case "FAILURE" => Right(RunResult.FAILURE)
    case other     => Left(s"Unknown run result: $other")
  },
  Encoder.encodeString.contramap(_.toString)
)

case class ProjectResult(
    path: String,
    result: RunResult,
    diff: Option[String] = None,
    report: Option[String] = None
) derives Codec

enum JobStatus:
  case PENDING, RUNNING, COMPLETED, FAILED

given Codec[JobStatus] = Codec.from(
  Decoder.decodeString.emap {
    case "PENDING"   => Right(JobStatus.PENDING)
    case "RUNNING"   => Right(JobStatus.RUNNING)
    case "COMPLETED" => Right(JobStatus.COMPLETED)
    case "FAILED"    => Right(JobStatus.FAILED)
    case other       => Left(s"Unknown job status: $other")
  },
  Encoder.encodeString.contramap(_.toString)
)

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
