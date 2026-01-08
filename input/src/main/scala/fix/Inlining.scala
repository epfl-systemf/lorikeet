/*
rule = ParsedRule
 */
package fix

// This test checks increasingly complex scenarios for inlining nested functions
// In particular, it tests
// - basic metavariable usage in rewriting
// - substitution in rewriting
// - symbol testing in substitution
// - semantic return type matching

object Inlining:
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

  // Nested function should be inlined (semantic return type)
  def findAllAndPrint5(): Boolean =
    def iterate() =
      println("ok")
      true
    iterate()

  // Nested function should not be inlined (non matching semantic return type)
  def findAllAndPrint6(): Unit =
    def iterate() =
      println("ok")
      true
    iterate()
