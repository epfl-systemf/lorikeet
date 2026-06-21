package fix
package fs2semaphore

import cats.effect.IO
import cats.effect.concurrent.Semaphore

object FS2Semaphore {
  def lock(s: Semaphore[IO]): IO[Unit] =
    s.acquire

  class Counter(var n: Int) {
    def decrement: Unit = n -= 1
  }
  def lowerCount(c: Counter): Unit =
    c.decrement
}
