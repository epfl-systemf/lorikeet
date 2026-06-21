package fix
package fs2bracket

import fs2._
import cats.effect.IO

object FS2Brackets {

  def acquire = IO(99)
  def use(r: Int) = Stream.emit(r)
  def release(r: Int) = IO()

  val withNamedFunctions: Stream[IO, Int] =
    Stream.bracket(acquire)(release).flatMap(use)

  val withLambdas: Stream[IO, Int] =
    Stream
      .bracket(IO(99))(r => IO())
      .flatMap(r => Stream.emit(r))

  val withStrings: Stream[IO, String] =
    Stream
      .bracket(IO(println("acquire")))(_ => IO(println("release")))
      .flatMap(_ => Stream.emit("hello"))

  val withInts: Stream[IO, Int] =
    Stream
      .bracket(IO(42))(r => IO(println(s"releasing $r")))
      .flatMap(r => Stream.emits(List(r, r + 1, r + 2)))

  val withImport: Stream[IO, Int] = {
    import fs2.Stream._
    Stream.bracket(acquire)(release).flatMap(use)
  }
}
