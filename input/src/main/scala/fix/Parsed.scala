/*
rule = ParsedRule
 */
package fix

object Parsed:
  val l = List(1, 2, 3)
  val a = true

  infix def +(b: Int): Int = this.hashCode() + b

  val x = 1./(2) // assert: ParsedRule
  val y = x.-(3) // assert: ParsedRule
  val z = y.*(4) // assert: ParsedRule
  val w = z.+(5) // assert: ParsedRule
  val u = w./(6) // assert: ParsedRule
  val v = u - 7
  val t = v * 8
  val s = t + 9
  val r = s / 10

  // if (a) then { println("true"); true } else false // assert: ParsedRule

  // if !a then false else { println("false"); true }

  // if a then true else false // assert: ParsedRule

  // if a then { println("true"); true } else { println("false"); false }

  // if a then true else { println("false"); false }

  // if !l.isEmpty then // assert: ParsedRule
  //   if l.head < 0 then false
  //   else l.tail.forall(_ >= 0)
  // else true
