/*
rule = MetaRule
 */
package fix
package multstatements

object MultStatements:
  def sum(x: Int, y: Int): Int = {
    println("debug: added values")
    x + y
  }

  def processData(values: List[Int]): Int = {
    val sum = values.sum
    val doubled = sum * 2
    println("debug: doubled value")
    doubled + 10
  }

  def simpleBlock(n: Int): Int = {
    val a = n + 1
    println("debug: incremented")
    val b = a * 2
    b
  }
