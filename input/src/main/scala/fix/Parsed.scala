/*
rule = ParsedRule
 */
package fix

object Parsed:
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
