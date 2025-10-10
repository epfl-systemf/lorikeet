/*
rule = ParsedRule
 */
package fix

object Parsed:
  val l = List(1, 2, 3)
  val a = true

  if (a) then { println("true"); true } else false // assert: ParsedRule

  if !a then false else { println("false"); true }

  if a then true else false // assert: ParsedRule

  if a then { println("true"); true } else { println("false"); false }

  if a then true else { println("false"); false }

  if !l.isEmpty then // assert: ParsedRule
    if l.head < 0 then false
    else l.tail.forall(_ >= 0)
  else true
