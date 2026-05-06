/*
rule = MetaRule
 */
package fix
package fqn

object FullyQualifiedNames:
  val listValue = List(1, 2)
  val noneValue = None
  val s: String = "hello"
  val o: Option[Int] = Option(1)

object FullyQualifiedNames2:
  type String = Int
  val i: String = 42 // Should not be rewritten since its not a java.lang.String