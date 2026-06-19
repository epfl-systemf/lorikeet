import fs2._
import cats.effect.IO

object FS2Brackets {

  val simple: Stream[IO, String] =
    Stream
      .bracket(IO(println("acquire")))(_ => IO(println("release")))
      .flatMap(_ => Stream.emit("hello"))

  val withInts: Stream[IO, Int] =
    Stream
      .bracket(IO(42))(r => IO(println(s"releasing $r")))
      .flatMap(r => Stream.emits(List(r, r + 1, r + 2)))

  def acquire: IO[Int] = IO(println("open")).map(_ => 99)
  def use(r: Int): Stream[IO, Int] = Stream.emit(r)
  def release(r: Int): IO[Unit] = IO(println(s"close $r"))

  val withNamedFunctions: Stream[IO, Int] =
    Stream.bracket(acquire)(release).flatMap(use)

}
