/*
rule = ParsedRule
 */
package fix

// This test checks the "including" metasyntax

object Including:
  // Simplify a match
  def simplifyMatch(x: Option[Int]): String =
    x match
      case Some(v) => "wow"
      case _       => "ok"

    x match
      case Some(v) if v > 2 => "wow"
      case _                => "ok"

    x match
      case Some(v) => v.toString()
      case _       => "nah"

    x match
      case Some(1) => 1.toString()
      case _       => "ok"