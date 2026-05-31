// server/src/main/scala/Main.scala
import cats.effect._
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import api._
import scala.concurrent.duration._
import java.util.concurrent.ConcurrentHashMap
import cats.effect.std.Queue

object Main extends IOApp {

  val jobStore = ConcurrentHashMap[String, Job]()

  def workerLoop(queue: Queue[IO, String]): IO[Unit] = {
    for {
      // wait for a job ID to process
      jobId <- queue.take
      _ <- IO.println(s"Worker: Picked up Job $jobId")

      jobOpt = Option(jobStore.get(jobId))
      _ <- jobOpt match {
        case Some(job) =>
          IO(jobStore.put(jobId, job.copy(status = JobStatus.RUNNING))) >>
            IO.println(
              s"Worker: Running refactor for ${job.request.projectPaths.size} projects..."
            ) >>
            IO.pure(
              LorikeetRunner.run(
                jobId,
                job.request.rule,
                job.request.projectPaths.head
              ) match {
                case LorikeetRunner.Success =>
                  IO.println(s"Worker: Job $jobId completed successfully")
                case LorikeetRunner.CompileError =>
                  IO.println(s"Worker: Job $jobId failed due to compile error")
              }
            ) >>
            IO(jobStore.put(jobId, job.copy(status = JobStatus.COMPLETED)))

        case None => IO.println(s"Worker Error: Job $jobId not found")
      }
      _ <- workerLoop(queue)
    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      queue <- Queue.unbounded[IO, String]
      refactorServerEndpoint = Endpoints.runRefactor.serverLogic { req =>
        val job =
          Job(request = req, status = JobStatus.PENDING, results = Map.empty)
        IO(jobStore.put(job.id, job)) >> queue.offer(job.id) >> IO.pure(
          Right(job)
        )
      }
      swaggerEndpoints = SwaggerInterpreter()
        .fromServerEndpoints[IO](
          List(refactorServerEndpoint),
          "Lorikeet API",
          "1.0.0"
        )
      routes = Http4sServerInterpreter[IO]()
        .toRoutes(List(refactorServerEndpoint) ++ swaggerEndpoints)
        .orNotFound
      server = EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(routes)
        .build
      _ <- workerLoop(queue).start // worker in background
      _ <- server.useForever
    } yield ExitCode.Success
  }
}
