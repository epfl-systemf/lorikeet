/*
rule = MetaRule
 */
package fix

object MultParamArg:
  // Nested function should be inlined
  def findAllAndPrint(param1: String, param2: Int): Boolean =
    def iterate(paramA: String, paramB: Int): Boolean =
      println("ok")
      true
    iterate(param1, param2)
