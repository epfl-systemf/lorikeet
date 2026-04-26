package lorikeet

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import lorikeet.metasyntax.common._
import lorikeet.metasyntax.pattern._

type MatchResult = Option[Bindings]

case class Matcher()(using
    doc: SemanticDocument,
    matchOptions: MatchOptions
) extends AbstractMatcher:

  def compare(
      pat: Tree,
      cand: Tree,
      bindings: Bindings
  ): MatchResult =
    // check package constraints if specified
    matchOptions.onlyPackages match
      case Some(pkgs) =>
        val fullPackage = doc.tree.collect {
          case p: Pkg => p.ref.toString()
        }.mkString(".")
        if pkgs.exists(pkg => fullPackage.startsWith(pkg)) then
          compareTrees(pat, cand, bindings)
        else None
      case None => compareTrees(pat, cand, bindings)

  def compareTrees(
      pat: Tree,
      cand: Tree,
      bindings: Bindings
  ): MatchResult =
    pat match
      case PatternBlock(pattern) =>
        matchWithPattern(pattern, cand, bindings)
      // Wildcard + metavariables
      case WildcardSymbol() => Some(bindings)
      case MetaVar(name) =>
        (pat, cand) match
          case (_: Term, t: Term) => bindings.add[Term](name, t)
          case (_: Type, t: Type) => bindings.add[Type](name, t)
          case (_: Term, p: Pat)  =>
            // Special handling due to special meaning of backticks in patterns
            compareTrees(Pat.Var(Term.Name("?" + name)), cand, bindings)
          case _ => None

      // Optional semantic handling for fully qualified names in patterns
      case SymbolMatching.FullyQualifiedName(fqn)
          if matchOptions.matchQualifiedNamesBySymbol  =>
        cand match
            case SymbolMatching.FullyQualifiedName(_) =>
              val x = SymbolMatching.getTreeSymbolName(cand)
              x match
                case Some(name) if name == fqn => Some(bindings)
                case _ => None 
            case _ => None

      // Special handling for type ascriptions
      // For options with matchAscriptions = false:
      // don't match the types literally: check the symbol type instead
      case WithOptionalType(extPat, patType, typeField)
          if !matchOptions.matchAscriptions =>
        cand match
          case WithOrWithoutOptionalType(extCand, candType, _) =>
            compareWithOptionalAscription(
              extPat,
              extCand,
              bindings,
              patType,
              candType,
              typeField
            )
          case _ => None
      case FunctionWithOptionalParamTypes(
            Term.ParamClause(patParams, patParamMods),
            _
          ) if !matchOptions.matchAscriptions =>
        cand match
          case FunctionWithOptionalParamTypes(
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
      // Mult Params
      // Mods ignored (not bound)
      case Term.ParamClause(patParams, _) =>
        cand match
          case Term.ParamClause(candParams, _) =>
            MultMatching.matchListWithMults(
              patParams,
              candParams,
              bindings,
              {
                case MultParam(_, _) => true
                case _                => false
              },
              (multPat, taken, b) =>
                multPat match
                  case MultParam(name, tpe) =>
                    val names = taken.collect {
                      // For now, match simple parameter structure for candidates
                      // Doesn't bind modifiers or default values
                      // Requires no ommitted types
                      case Term.Param(_, n: Term.Name, Some(_), _) => n
                    }
                    val types = taken.flatMap(_.decltpe)
                    if names.size != taken.size || types.size != taken.size then
                      None
                    else
                      val bindingsWithNames = name match
                          case None    => Some(bindings)
                          case Some(n) => bindings.add[List[Term.Name]](n, names)
                      val bindingsWithTypes = tpe match
                          case None => bindingsWithNames
                          case Some(t) =>
                            bindingsWithNames.flatMap(b => b.add[List[Type]](t, types))
                      bindingsWithTypes
                  case _ => None,
              compareTrees
            )

          case _ => None

      // Mult Args
      // Mods ignored (not bound)
      case Term.ArgClause(patArgs, _) =>
        cand match
          case Term.ArgClause(candArgs, _) =>
            MultMatching.matchListWithMults(
              patArgs,
              candArgs,
              bindings,
              {
                case MultName(_) => true
                case _           => false
              },
              (multPat, taken, b) =>
                multPat match
                  case MultName(name) => b.add[List[Term]](name, taken)
                  case _              => None,
              compareTrees
            )
          case _ => None

      // Mult Statements
      case Term.Block(patStats) =>
        cand match
          case Term.Block(candStats) =>
            MultMatching.matchListWithMults(
              patStats,
              candStats,
              bindings,
              {
                case MultName(_) => true
                case _           => false
              },
              (multPat, taken, b) =>
                multPat match
                  case MultName(name) => b.add[List[Stat]](name, taken)
                  case _              => None,
              compareTrees
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
      typeFieldName: Option[String]
  ): MatchResult =
    val typeMatch = (patType, candType) match
      case (Some(patTpe), Some(candTpe)) =>
        // Both have explicit types - compare structurally
        compareTrees(patTpe, candTpe, bindings)
      case (Some(tpe), None) =>
        // Pattern has type, candidate doesn't - use semantic matching
        SemanticTypeMatching.matchTreeType(cand, tpe, bindings)
      case (None, _) =>
        // Pattern has no type - accept any candidate type
        Some(bindings)

    typeMatch.flatMap(newBindings =>
      typeFieldName match
        case Some(fieldName) =>
          compareProducts(pat, cand, newBindings, Set(fieldName))
        case None => // Case of actual ascriptions
          compareTrees(pat, cand, newBindings)
    )

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

  def matchWithPattern(
      pat: Tree,
      candidate: Tree,
      bindings: Bindings
  ): MatchResult =
    pat match
      case InPattern.Escape(arg) =>
        compareTrees(arg, candidate, bindings)
      case InPattern.Alternative(a, b) =>
        matchWithPattern(a, candidate, bindings) match
          case s @ Some(_) => s
          case None        => matchWithPattern(b, candidate, bindings)
      case InPattern.Wildcard() => Some(bindings)
      case InPattern.Binding(name, v) =>
        matchWithPattern(v, candidate, bindings).flatMap { newBindings =>
          candidate match
            case t: Term => newBindings.add[Term](name, t)
            case _       => None
        }
      case InPattern.Including(v, uses) if uses.forall(isUsesPattern(_)) =>
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
        bindings.get[Term](name.stripPrefix("?")) match
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
