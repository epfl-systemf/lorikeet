/*
rule = ParsedRule
 */
package fix

object Parsed:
  // Nested function should be inlined
  def findAllAndPrint(): Boolean =
    def iterate() =
      println("ok")
      true
    iterate()
