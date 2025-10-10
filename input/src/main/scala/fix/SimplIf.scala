/*
rule = SimplIfRule
 */
package fix

object SimplIf:
  val cond: Boolean = true
  val foundFirstLevel: Boolean =
    if (cond) then
      println("test"); true
    else false

  def allPositiveOrZero(l: List[Int]): Boolean =
    if !l.isEmpty then
      if l.head < 0 then false
      else allPositiveOrZero(l.tail)
    else true
