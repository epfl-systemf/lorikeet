package fix
package quantified

object Quantified:
  def statements(): Unit = {
    println("start")
    val middle = 1
    println("done")
  }

  def noMiddle(): Unit = {
    println("start")
    println("end")
  }

  def declaration(): Unit = {
    println("before")
    val next = 1
    println("changed")
  }

  def withParameter(value: Int): Int = 2
  def withoutParameter(): Int = 1

  def target(values: Int*): Int = values.sum
  def replacement(values: Int*): Int = values.sum
  def withArgument(): Int = replacement(1)
  def withoutArgument(): Int = target()
