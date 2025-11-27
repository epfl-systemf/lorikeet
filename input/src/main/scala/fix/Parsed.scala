/*
rule = ParsedRule
 */
package fix

object Parsed:
  // Expand a map
  def expand(l: List[Int]): List[Int] =
    l.map((x: Int) => x * 2)
  def expand2(l: List[Int]): List[Int] =
    l.map((x) => x * x)
  def expand3(l: List[Int]): List[Int] =
    l.map(x => x + 1)

  // val test = Array(1) == Array(1)
