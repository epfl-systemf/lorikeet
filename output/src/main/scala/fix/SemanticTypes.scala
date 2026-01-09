package fix

object SemanticTypes:
  val x = 1 == 2
  val a = List(1)
  val y = List(1) == a
  case class Test[A](value: A)
  val b = Test(1)
  val z = (Test(1): Test[Int]) == b
