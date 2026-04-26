/*
rule = MetaRule
 */
package fix
package fqn

object FullyQualifiedNames:
  val listValue = Vector(1, 2)
  val noneValue = Option.empty
  val s: java.lang.String = "hello"
  val o: scala.Option[Int] = scala.Option(1)

object FullyQualifiedNames2:
  type String = Int
  val i: String = 42
