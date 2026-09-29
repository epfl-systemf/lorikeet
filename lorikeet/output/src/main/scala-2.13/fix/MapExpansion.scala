package fix

object MapExpansion {
  def expand(l: List[Int]): List[Int] =
    l match {
      case Nil => Nil
      case head :: tail =>
        (head * 2) :: expand(tail)
    }
}
