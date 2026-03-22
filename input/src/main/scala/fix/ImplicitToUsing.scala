/*
rule = MetaRule
 */
package fix

// This test checks @mult on using/implicit

object ImplicitToUsing:
  def function(a: Int, b: String)(implicit c: String, d: Boolean): Boolean = 
    d