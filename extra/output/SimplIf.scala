package fix

object SimplIf:
  val cond: Boolean = true
  val foundFirstLevel: Boolean =
    (cond && { println("test"); true })

  def allPositiveOrZero(l: List[Int]): Boolean =
    (!(!l.isEmpty) || (!(l.head < 0) && allPositiveOrZero(l.tail)))
