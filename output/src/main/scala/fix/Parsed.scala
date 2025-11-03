package fix

object Parsed:
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