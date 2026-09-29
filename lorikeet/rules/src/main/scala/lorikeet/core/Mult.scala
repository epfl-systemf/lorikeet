package lorikeet.core

import scala.meta.Tree

object MultMatching:
  def matchListWithMults[T <: Tree](
      pats: List[Tree],
      cands: List[T],
      bindings: Bindings,
      multMinimum: Tree => Option[Int],
      bindMult: (Tree, List[T], Bindings) => Option[Bindings],
      compareTrees: (Tree, Tree, Bindings) => Option[Bindings]
  ): Option[Bindings] = (pats, cands) match {
    case (Nil, Nil)                                 => Some(bindings)
    case (multPat :: patRest, _) if multMinimum(multPat).nonEmpty =>
      // Greedy: try to give mult as much as possible,
      // then back off until the rest matches
      // In the future we could consider adding the opposite approach
      val minAfter = patRest.map(item => multMinimum(item).getOrElse(1)).sum
      val maxTake = cands.size - minAfter
      val minTake = multMinimum(multPat).get
      if maxTake < minTake then None
      else
        val attempts = (maxTake to minTake by -1)
        attempts.iterator
          .map { n =>
            val (taken, remaining) = cands.splitAt(n)
            val newBindings = bindMult(multPat, taken, bindings)
            newBindings.flatMap(b =>
              matchListWithMults(
                patRest,
                remaining,
                b,
                multMinimum,
                bindMult,
                compareTrees
              )
            )
          }
          .find(_.isDefined)
          .flatten
    case (p :: patRest, c :: candRest) =>
      compareTrees(p, c, bindings).flatMap(b =>
        matchListWithMults(patRest, candRest, b, multMinimum, bindMult, compareTrees)
      )
    case _ => None
  }
