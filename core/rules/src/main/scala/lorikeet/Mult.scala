package lorikeet

import scala.meta.Tree
import scala.tools.nsc.doc.html.HtmlTags.Li

object MultMatching:
  def matchListWithMults[T <: Tree](
      pats: List[Tree],
      cands: List[T],
      bindings: Bindings,
      isMult: Tree => Boolean,
      bindMult: (Tree, List[T], Bindings) => Option[Bindings],
      compareTrees: (Tree, Tree, Bindings) => Option[Bindings]
  ): Option[Bindings] = (pats, cands) match {
    case (Nil, Nil)                                 => Some(bindings)
    case (multPat :: patRest, _) if isMult(multPat) =>
      // Greedy: try to give mult as much as possible,
      // then back off until the rest matches
      // In the future we could consider adding the opposite approach
      val minAfter = patRest.count(item => !isMult(item))
      val maxTake = cands.size - minAfter
      if maxTake < 0 then None
      else
        val attempts = (maxTake to 0 by -1)
        attempts.iterator
          .map { n =>
            val (taken, remaining) = cands.splitAt(n)
            val newBindings = bindMult(multPat, taken, bindings)
            newBindings.flatMap(b =>
              matchListWithMults(
                patRest,
                remaining,
                b,
                isMult,
                bindMult,
                compareTrees
              )
            )
          }
          .find(_.isDefined)
          .flatten
    case (p :: patRest, c :: candRest) =>
      compareTrees(p, c, bindings).flatMap(b =>
        matchListWithMults(patRest, candRest, b, isMult, bindMult, compareTrees)
      )
    case _ => None
  }
