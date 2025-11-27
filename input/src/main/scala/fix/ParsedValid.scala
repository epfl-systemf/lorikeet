/*
rule = ParsedRule
 */
package fix

object ParsedValid:
  val l = List(1, 2, 3)
  val a = true

  infix def +(b: Int): Int = this.hashCode() + b

  val x = 1./(2)
  val y = x.-(3)
  val z = y.*(4)
  val w = z.+(5)
  val u = w./(6)
  val v = u - 7
  val t = v * 8
  val s = t + 9
  val r = s / 10

  // Nested function should be inlined
  def findAllAndPrint(): Boolean =
    def iterate(): Boolean =
      println("ok")
      true
    iterate()

  // No change
  def findAllAndPrint2(): Boolean =
    def iterate(): Boolean =
      println("ok")
      true
    findAllAndPrint()

  // Nested function with recursion should be inlined
  def findAllAndPrint3(): Boolean =
    def iterate(): Boolean =
      println("ok")
      iterate()
    iterate()

  // Shadowed name should not be substituted
  def findAllAndPrint4(): Boolean =
    def iterate(): Boolean =
      println("ok")
      def iterate() = false
      iterate()
    iterate()

  // Nested function should be inlined (semantic return type)
  def findAllAndPrint5(): Boolean =
    def iterate() =
      println("ok")
      true
    iterate()

  // Nested function should not be inlined (non matching semantic return type)
  def findAllAndPrint6(): Unit =
    def iterate() =
      println("ok")
      true
    iterate()

  // Lambda to placeholder syntax
  val words = List("cat", "dog", "elephant")
  words.map((x) => x.length())
  words.foldLeft(0)((acc, x) => acc + x.length())
  // Remain unchanged
  words.foldLeft("")((acc, x) => acc + x)

  var test = 0 // assert: ParsedRule