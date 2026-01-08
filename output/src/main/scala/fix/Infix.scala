package fix

object Infix:
  infix def +(b: Int): Int = this.hashCode() + b

  val x = 1 / 2
  val y = x - 3
  val z = y * 4
  val w = z + 5
  val u = w / 6
  val v = u - 7
  val t = v * 8
  val s = t + 9
  val r = s / 10
