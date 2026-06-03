// server/src/main/scala/Main.scala
import cats.effect._
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import cats.implicits._
import api._
import scala.concurrent.duration._
import cats.effect.std.Queue
import cats.effect.Ref // Purely functional atomic reference
import api.Endpoints.getStatus

object Main extends IOApp {

  private def updateJobResult(
      jobStore: Ref[IO, Map[String, Job]],
      jobId: String,
      projectResult: ProjectResult
  ): IO[Unit] =
    jobStore.update { currentStore =>
      currentStore.get(jobId) match {
        case Some(job) =>
          currentStore.updated(
            jobId,
            job.copy(
              status = JobStatus.RUNNING,
              results = job.results + (projectResult.path -> projectResult)
            )
          )
        case None => currentStore
      }
    }

  def workerLoop(
      queue: Queue[IO, String],
      jobStore: Ref[IO, Map[String, Job]]
  ): IO[Unit] = {
    for {
      // wait for a job ID to process
      jobId <- queue.take
      _ <- IO.println(s"Worker: Picked up Job $jobId")

      store <- jobStore.get
      jobOpt = store.get(jobId)

      _ <- jobOpt match {
        case Some(job) =>
          for {
            // Update state to RUNNING
            _ <- jobStore.update(
              _.updated(jobId, job.copy(status = JobStatus.RUNNING))
            )
            _ <- IO.println(
              s"Worker: Running refactor for ${job.request.projectPaths.size} projects..."
            )

            projectResults <- job.request.projectPaths.traverse { projectPath =>
              IO(
                LorikeetRunner.run(jobId, job.request.rule, projectPath)
              )
            }

            _ <- projectResults.traverse_ { projectResult =>
              projectResult.result match {
                case api.RunResult.Success =>
                  IO.println(
                    s"Worker: Job $jobId completed successfully for ${projectResult.path}"
                  )
                case api.RunResult.Failure =>
                  IO.println(
                    s"Worker: Job $jobId failed for ${projectResult.path}"
                  )
              }
            }

            finalStatus =
              if (projectResults.forall(_.result == api.RunResult.Success)) then
                JobStatus.COMPLETED
              else JobStatus.FAILED

            _ <- projectResults.traverse_ { projectResult =>
              updateJobResult(jobStore, jobId, projectResult)
            }
            _ <- jobStore.update { currentStore =>
              currentStore.get(jobId) match {
                case Some(j) =>
                  currentStore.updated(jobId, j.copy(status = finalStatus))
                case None => currentStore
              }
            }
            _ <- IO.println(
              s"Worker: Job $jobId finished with status $finalStatus"
            )
          } yield ()

        case None => IO.println(s"Worker Error: Job $jobId not found")
      }
      _ <- workerLoop(queue, jobStore)
    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      queue <- Queue.unbounded[IO, String]
      jobStore <- Ref.of[IO, Map[String, Job]](
        Map.empty
      )

      refactorServerEndpoint = Endpoints.runRefactor.serverLogic { req =>
        val job =
          Job(request = req, status = JobStatus.PENDING, results = Map.empty)
        for {
          _ <- jobStore.update(_.updated(job.id, job))
          _ <- queue.offer(job.id)
        } yield Right(job)
      }

      getStatusEndpoint = Endpoints.getStatus.serverLogic { jobId =>
        jobStore.get.map(_.get(jobId)).flatMap {
          case Some(job) => IO.pure(Right(job))
          case None      =>
            IO.pure(
              Left("Job not found")
            )
        }
      }

      swaggerEndpoints = SwaggerInterpreter()
        .fromServerEndpoints[IO](
          List(refactorServerEndpoint, getStatusEndpoint),
          "Lorikeet API",
          "1.0.0"
        )

      routes = Http4sServerInterpreter[IO]()
        .toRoutes(
          List(refactorServerEndpoint, getStatusEndpoint) ++ swaggerEndpoints
        )
        .orNotFound

      serverResource = EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(routes)
        .build

      _ <- workerLoop(queue, jobStore).background.use { _ =>
        serverResource.useForever
      }

    } yield ExitCode.Success
  }
}
