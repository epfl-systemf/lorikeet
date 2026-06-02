package lorikeet.core

import scala.meta.Tree

trait AbstractMatcher:
  def compareTrees(a: Tree, b: Tree, bindings: Bindings): MatchResult

  def compareProducts(
      pat: Product,
      cand: Product,
      bindings: Bindings,
      skipFields: Set[String] = Set.empty
  ): MatchResult =
    val prodStruc =
      pat.productPrefix == cand.productPrefix &&
        pat.productArity == cand.productArity

    if (prodStruc)
    then
      pat.productIterator
        .zip(cand.productIterator)
        .zip(pat.productElementNames)
        .foldLeft[MatchResult](Some(bindings)) {
          case (None, _) => None
          case (Some(b), ((p, c), name)) =>
            if skipFields.contains(name) then Some(b)
            else compareFields(p, c, b)
        }
    else None

  def compareFields(pat: Any, cand: Any, bindings: Bindings): MatchResult =
    (pat, cand) match
      // Trees
      case (p: Tree, c: Tree) => this.compareTrees(p, c, bindings)
      // Options
      case (Some(pv), Some(cv))              => compareFields(pv, cv, bindings)
      case (None, None)                      => Some(bindings)
      case (Some(_), None) | (None, Some(_)) => None
      // Iterables
      case (p: Iterable[_], c: Iterable[_]) =>
        if p.size == c.size then
          p.zip(c).foldLeft[MatchResult](Some(bindings)) {
            case (None, _)           => None
            case (Some(b), (pp, cp)) => compareFields(pp, cp, b)
          }
        else None
      // Other fields
      case _ => if pat == cand then Some(bindings) else None

      // NOTE : Perhaps we don't want to use "Iterable" above, as it may
      // not be the right behavior for certain things like Strings...

object StrictTreeMatcher extends AbstractMatcher:
  def compareTrees(a: Tree, b: Tree, bindings: Bindings): MatchResult =
    compareProducts(a, b, bindings)
