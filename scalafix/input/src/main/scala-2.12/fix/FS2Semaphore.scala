/*
rule = MetaRule
 */
package fix
package fs2semaphore

import cats.effect.IO
import fs2.async.mutable.Semaphore

object FS2Semaphore {
  def lock(s: Semaphore[IO]): IO[Unit] =
    s.decrement

  class Counter(var n: Int) {
    def decrement: Unit = n -= 1
  }
  def lowerCount(c: Counter): Unit =
    c.decrement
}
