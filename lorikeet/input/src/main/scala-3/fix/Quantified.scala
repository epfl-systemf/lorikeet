/*
rule = MetaRule
 */
package fix
package quantified

object Quantified:
  def statements(): Unit = {
    println("start")
    val middle = 1
    println("end")
  }

  def noMiddle(): Unit = {
    println("start")
    println("end")
  }

  def declaration(): Unit = {
    println("before")
    val next = 1
    println("after")
  }

  def withParameter(value: Int): Int = 1
  def withoutParameter(): Int = 1

  def target(values: Int*): Int = values.sum
  def replacement(values: Int*): Int = values.sum
  def withArgument(): Int = target(1)
  def withoutArgument(): Int = target()
