package fix
package multstatements

object MultStatements:
  def sum(x: Int, y: Int): Int = {
    x + y
  }

  def processData(values: List[Int]): Int = {
    val sum = values.sum
    val doubled = sum * 2
    doubled + 10
  }

  def simpleBlock(n: Int): Int = {
    val a = n + 1
    val b = a * 2
    b
  }
