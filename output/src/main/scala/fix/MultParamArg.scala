package fix

object MultParamArg:
  // Nested function should be inlined
  def findAllAndPrint(param1: String, param2: Int): Boolean =
    println(param1)
    println(param2)
    true
