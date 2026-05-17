/*
rule = MetaRule
 */
package fix

// This test checks @mult on using/implicit

object ImplicitToUsing:
  def function(a: Int, b: String)(using c: String, d: Boolean): Boolean = 
    d