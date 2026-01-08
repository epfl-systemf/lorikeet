package fix

object Inlining:
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

  // Nested function should be inlined (semantic return type)
  def findAllAndPrint5(): Boolean =
    println("ok")
    true

  // Nested function should not be inlined (non matching semantic return type)
  def findAllAndPrint6(): Unit =
    def iterate() =
      println("ok")
      true
    iterate()
