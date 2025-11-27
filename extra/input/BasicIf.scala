/*
rule = BasicIfRule
 */
package fix

object BasicIf:
  def example(): Boolean =
    val a = {println("hello world"); true}
    if (a) then true
    else false
