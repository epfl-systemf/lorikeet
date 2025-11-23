package fix

object ParsedValid:
  val l = List(1, 2, 3)
  val a = true

  infix def +(b: Int): Int = this.hashCode() + b

  val x = 1 / 2
  val y = x - 3
  val z = y * 4
  val w = z + 5
  val u = w / 6
  val v = u - 7
  val t = v * 8
  val s = t + 9
  val r = s / 10

  // Nested function should be inlined
  def findAllAndPrint(): Boolean =
    println("ok")
    true

  // No change
  def findAllAndPrint2(): Boolean =
    def iterate(): Boolean =
      println("ok")
      true
    findAllAndPrint()

  // Nested function with recursion should be inlined
  def findAllAndPrint3(): Boolean =
    println("ok")
    findAllAndPrint3()

  // Shadowed name should not be substituted
  def findAllAndPrint4(): Boolean =
    println("ok")
    def iterate() = false
    iterate()
