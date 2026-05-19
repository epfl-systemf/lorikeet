/*
rule = MetaRule
 */
package fix

// This test checks substitution
// and contextual type matching for lambda parameter types
// among other things

object MapExpansion {
  def expand(l: List[Int]): List[Int] =
    l.map((x: Int) => x * 2)
}
