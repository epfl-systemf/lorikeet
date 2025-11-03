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
