package fix

object Parsed:
  // Nested function should be inlined
  def findAllAndPrint(): Boolean =
    println("ok")
    true