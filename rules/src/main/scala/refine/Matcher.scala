package refine

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3

type MatchResult = Option[Bindings]

case class Matcher()(using
    doc: SemanticDocument,
    matchOptions: MatchOptions
):

  def compareTrees(
      pat: Tree,
      cand: Tree,
      bindings: Bindings
  ): MatchResult =
    pat match
      case Syntax.PatternBlock(pattern) =>
        matchWithPattern(pattern, cand, bindings)
      // Wildcard + binding for symbols
      case Syntax.SymbolBind(name) =>
        (pat, cand) match
          case (_: Term, t: Term) => bindings.checkAddTerm(name, t)
          case (_: Type, t: Type) => bindings.checkAddType(name, t)
          case (_: Term, p: Pat)  =>
            // Special handling due to special meaning of backticks in patterns
            compareTrees(Pat.Var(Term.Name("?" + name)), cand, bindings)
          case _ => None
      // Special handling for type ascriptions
      // For options with matchAscriptions = false:
      // don't match the types literally: check the symbol type instead
      case Syntax.WithOptionalType(_, patType, typeField)
          if !matchOptions.matchAscriptions =>
        cand match
          case Syntax.WithOptionalType(_, candType, _) =>
            compareWithOptionalAscription(
              pat,
              cand,
              bindings,
              patType,
              candType,
              typeField
            )
          case _ => None
      case Syntax.FunctionWithOptionalParamTypes(
            Term.ParamClause(patParams, patParamMods),
            _
          ) if !matchOptions.matchAscriptions =>
        cand match
          case Syntax.FunctionWithOptionalParamTypes(
                Term.ParamClause(candParams, candParamMods),
                _
              ) =>
            compareFunctionWithSemanticTypes(
              pat,
              cand,
              bindings,
              patParams,
              candParams,
              patParamMods,
              candParamMods
            )
          case _ => None
      // General case
      case _ => compareProducts(pat, cand, bindings)

  /** Handle optional type ascriptions with semantic type matching when pattern
    * has type but candidate doesn't
    */
  private def compareWithOptionalAscription(
      pat: Tree,
      cand: Tree,
      bindings: Bindings,
      patType: Option[Type],
      candType: Option[Type],
      typeFieldName: String
  ): MatchResult =
    (patType, candType) match
      case (Some(patTpe), Some(_)) =>
        // Both have explicit types - compare structurally
        compareProducts(pat, cand, bindings)
      case (Some(tpe), None) =>
        // Pattern has type, candidate doesn't - use semantic matching
        SemanticTypeMatching.matchTreeType(cand, tpe, bindings).flatMap {
          newBindings =>
            compareProducts(pat, cand, newBindings, Set(typeFieldName))
        }
      case (None, _) =>
        // Pattern has no type - accept any candidate type
        compareProducts(pat, cand, bindings, Set(typeFieldName))

  /** Compare function parameters with semantic type matching
    */
  private def compareFunctionWithSemanticTypes(
      pat: Tree,
      cand: Tree,
      bindings: Bindings,
      patParams: List[Term.Param],
      candParams: List[Term.Param],
      patParamMods: Option[Mod],
      candParamMods: Option[Mod]
  ): MatchResult =
    // Compare parameters (with semantic type matching)
    val paramsMatch =
      if patParams.size != candParams.size then None
      else
        patParams
          .zip(candParams)
          .foldLeft[MatchResult](Some(bindings)) {
            case (Some(b), (patParam, candParam)) =>
              (patParam.decltpe, candParam.decltpe) match
                case (Some(patTpe), Some(_)) =>
                  compareProducts(patParam, candParam, b)
                case (Some(tpe), None) =>
                  SemanticTypeMatching
                    .matchTreeType(candParam, tpe, b)
                    .flatMap(newBindings =>
                      compareProducts(
                        patParam,
                        candParam,
                        newBindings,
                        Set("decltpe")
                      )
                    )
                case (None, _) =>
                  compareProducts(patParam, candParam, b, Set("decltpe"))
            case (None, _) => None
          }
    paramsMatch
      .flatMap(newBindings =>
        // Compare parameter modifiers
        compareFields(patParamMods, candParamMods, newBindings)
      )
      .flatMap(newBindings =>
        // Compare body
        compareProducts(pat, cand, newBindings, Set("paramClause"))
      )

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
      case (p: Tree, c: Tree) => compareTrees(p, c, bindings)
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

  def matchWithPattern(
      pat: Tree,
      candidate: Tree,
      bindings: Bindings
  ): MatchResult =
    pat match
      case Term.ApplyUnary(Term.Name("+"), arg) =>
        compareTrees(arg, candidate, bindings)
      case Term.ApplyInfix.After_4_6_0(
            a,
            Term.Name("|"),
            Type.ArgClause(Nil),
            Term.ArgClause(List(b), _)
          ) =>
        matchWithPattern(a, candidate, bindings) match
          case s @ Some(_) => s
          case None        => matchWithPattern(b, candidate, bindings)
      case Term.Placeholder() => Some(bindings)
      case Term.AnonymousFunction(f) =>
        matchWithPattern(f, candidate, bindings)
      case Term.ApplyInfix.After_4_6_0(
            Term.Name(name),
            Term.Name(":="),
            Type.ArgClause(Nil),
            Term.ArgClause(List(v), _)
          ) =>
        matchWithPattern(v, candidate, bindings).flatMap { newBindings =>
          candidate match
            case t: Term => newBindings.checkAddTerm(name, t)
            case _       => None
        }
      case Term.ApplyInfix.After_4_6_0(
            v,
            Term.Name("including"),
            Type.ArgClause(Nil),
            Term.ArgClause(uses, _)
          ) if uses.forall(isUsesPattern(_)) =>
        matchWithPattern(v, candidate, bindings).flatMap { newBindings =>
          if checkUses(uses, candidate, newBindings) then Some(newBindings)
          else None
        }
      case _ =>
        throw new Exception(s"Unsupported pattern: ${pat.syntax}")

  def isUsesPattern(
      tree: Tree
  ): Boolean =
    tree match
      case Term.Name(name)                                     => true
      case Term.SelectPostfix(Lit.Int(times), Term.Name(name)) => true
      case Term.SelectPostfix(
            Term.Apply.After_4_6_0(
              Term.Name("min" | "max"),
              Term.ArgClause(List(Lit.Int(times)), _)
            ),
            Term.Name(name)
          ) =>
        true
      case _ => false

  def checkUses(
      uses: List[Tree],
      candidate: Tree,
      bindings: Bindings
  ): Boolean =

    def countUses(trueName: String): Int =
      candidate.collect {
        case Term.Name(n) if n == trueName => n
      }.size

    def getTrueName(name: String): Option[String] =
      if name.startsWith("?") then
        bindings.getTerm(name.stripPrefix("?")) match
          case Some(Term.Name(n)) => Some(n)
          case Some(_)            => None
          case None => throw new Exception(s"No binding found for name: $name")
      else Some(name)

    uses.forall { use =>
      use match
        case Term.Name(name) =>
          // At least one use
          getTrueName(name).map(countUses).exists(_ > 0)
        case Term.SelectPostfix(Lit.Int(times), Term.Name(name)) =>
          // Exact number of uses
          getTrueName(name).map(countUses).contains(times)
        case Term.SelectPostfix(
              Term.Apply.After_4_6_0(
                Term.Name(bound @ ("min" | "max")),
                Term.ArgClause(List(Lit.Int(times)), _)
              ),
              Term.Name(name)
            ) =>
          // Minimum or maximum number of uses
          val useCount = getTrueName(name).map(countUses)
          bound match
            case "min" => useCount.exists(_ >= times)
            case "max" => useCount.exists(_ <= times)
        case _ => false
    }
