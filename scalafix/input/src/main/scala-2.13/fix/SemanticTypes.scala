/*
rule = MetaRule
 */
package fix

object SemanticTypes {
  // Should be rewritten without type ascription
  val x = (1: Int) == 2
  // Should be rewritten without type ascription
  val a = List(1)
  val y = (List(1): List[Int]) == a
  // Should stay the same
  case class Test[A](value: A)
  val b = Test(1)
  val z = (Test(1): Test[Int]) == b
}
