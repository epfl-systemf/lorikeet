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
