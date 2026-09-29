package fix

object MapExpansion:
  // Expand a map
  def expand(l: List[Int]): List[Int] =
    l match
      case Nil => Nil
      case head :: tail =>
        (head * 2) :: expand(tail)
  def expand2(l: List[Int]): List[Int] =
    l match
      case Nil => Nil
      case head :: tail =>
        (head * head) :: expand2(tail)
  def expand3(l: List[Int]): List[Int] =
    l match
      case Nil => Nil
      case head :: tail =>
        (head + 1) :: expand3(tail)