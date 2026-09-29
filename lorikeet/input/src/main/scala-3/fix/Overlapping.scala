/*
rule = MetaRule
 */
package fix.overlap

object Overlapping:
  def first(value: Int) = value
  def second(value: Int) = value
  def third(value: Int) = value

  val result = { first(1) }
