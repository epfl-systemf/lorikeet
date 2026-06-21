/*
rule = MetaRule
 */
package fix
package fs2debounce

import fs2._
import cats.effect._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

abstract class FS2Debounce[F[_]: ConcurrentEffect: Timer] {
  val scheduler: Scheduler
  val duration = 1.second
  Stream.eval(Effect[F].unit).through(scheduler.debounce(duration))
}
