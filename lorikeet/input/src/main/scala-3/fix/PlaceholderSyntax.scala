/*
rule = MetaRule
 */
package fix

// This test checks contextual type matching for lambda parameter types

object PlaceholderSyntax:
  // Lambda to placeholder syntax
  val words = List("cat", "dog", "elephant")
  words.map((x) => x.length())
  words.foldLeft(0)((acc, x) => acc + x.length())
  // Remain unchanged
  words.foldLeft("")((acc, x) => acc + x)