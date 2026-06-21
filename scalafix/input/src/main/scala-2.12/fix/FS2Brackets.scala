/*
rule = MetaRule
 */
package fix
package fs2bracket

import fs2._
import cats.effect.IO

object FS2Brackets {

  def acquire = IO(99)
  def use(r: Int) = Stream.emit(r)
  def release(r: Int) = IO()

  val withNamedFunctions: Stream[IO, Int] =
    Stream.bracket(acquire)(use, release)

  val withLambdas: Stream[IO, Int] =
    Stream
      .bracket(IO(99))(r => Stream.emit(r), r => IO())

  val withStrings: Stream[IO, String] =
    Stream.bracket(IO(println("acquire")))(
      _ => Stream.emit("hello"),
      _ => IO(println("release"))
    )

  val withInts: Stream[IO, Int] =
    Stream.bracket(IO(42))(
      r => Stream.emits(List(r, r + 1, r + 2)),
      r => IO(println(s"releasing $r"))
    )

  val withImport: Stream[IO, Int] = {
    import fs2.Stream._
    bracket(acquire)(use, release)
  }

}
