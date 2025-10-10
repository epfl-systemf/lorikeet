package fix

object UselessHelper:

  def outerFunction(p1: Int, p2: String): Boolean =
    if p1 > 0 then
      if p2.nonEmpty then true
      else false
    else false
