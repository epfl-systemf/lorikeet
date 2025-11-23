package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.collection.mutable.ListBuffer
import scala.io.Source._
import scala.util.{Try, Success, Failure}
import pureconfig._
import pureconfig.error.ConfigReaderFailures

case class MatchOptions(
    // Whether to match type ascriptions literally
    // or only compare the symbol type
    matchAscriptions: Boolean
)

case class RuleConfig(
    name: String,
    matchAscriptions: Option[Boolean],
    pattern: String,
    rewrite: Option[String]
) derives ConfigReader
case class RulesConfig(rules: List[RuleConfig]) derives ConfigReader
case class Rule(
    name: String,
    pattern: Tree,
    rewrite: Option[Tree],
    matchOptions: MatchOptions
)

case class LintMessage(t: Tree, r: String) extends Diagnostic {
  override def position: Position = t.pos
  override def message: String =
    s"Instance of parsed rule '$r' found at : \n${t.syntax}"
}

class ParsedRule extends SemanticRule("ParsedRule"):
  private def sameBinding(t1: Tree, t2: Tree)(using
      doc: SemanticDocument
  ): Boolean =
    (t1.symbol, t2.symbol) match
      case (Symbol.None, _) | (_, Symbol.None) => t1.structure == t2.structure
      case (s1, s2) => s1 == s2 && t1.structure == t2.structure

  object Bindings {
    val empty: Bindings = Bindings(Map.empty, Map.empty)
  }
  case class Bindings(
      terms: Map[String, Tree],
      types: Map[String, Type]
  ) {
    def checkAddTerm(name: String, tree: Tree)(using
        doc: SemanticDocument
    ): Option[Bindings] =
      terms.get(name) match
        case Some(t) if sameBinding(t, tree) => Some(this)
        case Some(x)                         => None
        case None =>
          tree match
            case t: Term => Some(this.copy(terms = terms + (name -> t)))
            case _ =>
              throw new Exception(
                s"Expected a Term for binding '$name', got: ${tree.syntax}"
              )
    def checkAddType(name: String, tree: Tree)(using
        doc: SemanticDocument
    ): Option[Bindings] =
      types.get(name) match
        case Some(t) if sameBinding(t, tree) => Some(this)
        case Some(x)                         => None
        case None =>
          tree match
            case t: Type => Some(this.copy(types = types + (name -> t)))
            case _ =>
              throw new Exception(
                s"Expected a Type for binding '$name', got: ${tree.syntax}"
              )
  }
  type MatchResult = Option[Bindings]

  def parseRulesConfig(): List[Rule] =
    val configFile = sys.env.get("RULES_CONF") match
      case None           => ".rules.conf"
      case Some(filename) => filename

    val configResults: Either[ConfigReaderFailures, RulesConfig] =
      ConfigSource.file(configFile).load[RulesConfig]

    val rules: List[RuleConfig] = configResults match
      case Right(r) => r.rules
      case Left(e) =>
        throw new Exception(
          s"Could not read rules from configuration file: $configFile. " +
            s"Error: ${e.prettyPrint()}"
        )

    val ruleTrees: List[Rule] = rules.map { rule =>
      val matchTree = rule.pattern.parse[Stat] match
        case Parsed.Success(t) => t
        case Parsed.Error(_, msg, _) =>
          throw new Exception(
            s"Could not parse match pattern for rule '${rule.name}': $msg"
          )
      val rewriteTree = rule.rewrite match
        case Some(rp) =>
          rp.parse[Stat] match
            case Parsed.Success(t) => Some(t)
            case Parsed.Error(_, msg, _) =>
              throw new Exception(
                s"Could not parse rewrite pattern for rule '${rule.name}': $msg"
              )
        case None => None
      val matchOptions = MatchOptions(
        matchAscriptions = rule.matchAscriptions.getOrElse(false)
      )
      Rule(rule.name, matchTree, rewriteTree, matchOptions)
    }

    ruleTrees

  override def fix(implicit doc: SemanticDocument): Patch =

    val ruleTrees = parseRulesConfig()

    val result = collectTopLevelMatches(
      doc.tree,
      { case t =>
        ruleTrees
          .flatMap { case Rule(n, p, r, mo) =>
            given MatchOptions = mo
            compareTrees(p, t, Bindings.empty).map { bindings =>
              r match
                case None =>
                  // Lint only
                  Patch.lint(LintMessage(t, n))
                case Some(r) =>
                  // Rewrite
                  val rewrittenTree = applyBindings(r, bindings)
                  Patch.replaceTree(t, rewrittenTree.syntax)
            }
          }
          .headOption
          .getOrElse(Patch.empty)

      }
    )

    result.asPatch

  def compareTrees(
      pat: Tree,
      cand: Tree,
      bindings: Bindings
  )(using doc: SemanticDocument, matchOptions: MatchOptions): MatchResult =
    pat match
      // Special handling for particular constructs
      case Term.Apply.After_4_6_0(
            Term.Name("?"),
            Term.ArgClause(List(Term.Block(List(arg))), _)
          ) =>
        matchWithPattern(arg, cand, bindings)
      case Term.AnonymousFunction(
            Term.Apply.After_4_6_0(
              Term.Name("?"),
              Term.ArgClause(List(Term.Block(List(arg))), _)
            )
          ) =>
        matchWithPattern(arg, cand, bindings)
      // Wildcard + binding for symbols
      case Term.Name(name) if name.startsWith("?") =>
        bindings.checkAddTerm(name.stripPrefix("?"), cand)
      case Type.Name(name) if name.startsWith("?") =>
        bindings.checkAddType(name.stripPrefix("?"), cand)
      // Special handling for type ascriptions
      // For options with matchAscriptions = false:
      // don't match the types literally: check the symbol type instead
      case Defn.Def.After_4_7_3(mods, name, params, decltpe, body)
          if !matchOptions.matchAscriptions =>
        cand match
          case Defn.Def.After_4_7_3(_, _, _, candDecltpe, _) =>
            (decltpe, candDecltpe) match
              case (Some(patTpe), Some(candTpe)) =>
                compareTrees(patTpe, candTpe, bindings) match
                  case Some(newBindings) =>
                    compareProducts(pat, cand, newBindings, Set("decltpe"))
                  case None => None
              case (Some(tpe), None) =>
                matchTreeSemTypeWithAscription(cand, tpe, bindings) match
                  case Some(newBindings) =>
                    compareProducts(pat, cand, newBindings, Set("decltpe"))
                  case None => None
              case (None, _) =>
                // Pattern has no declared type - accept any candidate type
                compareProducts(pat, cand, bindings, Set("decltpe"))
          case _ => None
      case Defn.Val(mods, pats, decltpe, t) if !matchOptions.matchAscriptions =>
        cand match
          case Defn.Val(_, _, candDecltpe, _) =>
            (decltpe, candDecltpe) match
              case (Some(patTpe), Some(candTpe)) =>
                compareTrees(patTpe, candTpe, bindings) match
                  case Some(newBindings) =>
                    compareProducts(pat, cand, newBindings, Set("decltpe"))
                  case None => None
              case (Some(tpe), None) =>
                matchTreeSemTypeWithAscription(cand, tpe, bindings) match
                  case Some(newBindings) =>
                    compareProducts(pat, cand, newBindings, Set("decltpe"))
                  case None => None
              case (None, _) =>
                // Pattern has no declared type - accept any candidate type
                compareProducts(pat, cand, bindings, Set("decltpe"))
          case _ => None
      case Term.Ascribe(t, tpe) if !matchOptions.matchAscriptions =>
        cand match
          case Term.Ascribe(candT, candTpe) =>
            // Candidate is also an ascription - compare exactly
            compareProducts(pat, cand, bindings)
          case _ =>
            // Candidate is not an ascription - match type semantically
            matchTreeSemTypeWithAscription(cand, tpe, bindings) match
              case Some(newBindings) =>
                compareProducts(pat, cand, newBindings, Set("tpe"))
              case None => None
      case Term.Function.After_4_6_0(paramClause, body)
          if !matchOptions.matchAscriptions =>
        // TODO: This part is incomplete and needs
        // to be updated
        cand match
          case c: Term.Function =>
            // Compare params clause
            val paramsMatch =
              paramClause.values.zip(c.paramClause.values).forall {
                case (patParam, candParam) =>
                  patParam.decltpe match
                    case Some(tpe) =>
                      matchTreeSemTypeWithAscription(
                        candParam,
                        tpe,
                        bindings
                      ).isDefined // MAKE INTO FOLDLEFT
                    case None =>
                      compareFields(
                        patParam.decltpe,
                        candParam.decltpe,
                        bindings
                      ).isDefined
              } && compareFields(
                paramClause.mod,
                c.paramClause.mod,
                bindings
              ).isDefined
            if !paramsMatch then None
            else
              compareProducts(
                pat,
                cand,
                bindings,
                Set("paramClause")
              )
          case _ => None
      // General case
      case _ => compareProducts(pat, cand, bindings)

  def compareProducts(
      pat: Product,
      cand: Product,
      bindings: Bindings,
      skipFields: Set[String] = Set.empty
  )(using doc: SemanticDocument, matchOptions: MatchOptions): MatchResult =
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

  def compareFields(pat: Any, cand: Any, bindings: Bindings)(using
      doc: SemanticDocument,
      matchOptions: MatchOptions
  ): MatchResult =
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
  )(using doc: SemanticDocument, matchOptions: MatchOptions): MatchResult =
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
          newBindings.checkAddTerm(name, candidate)
        }
      case _ =>
        throw new Exception(s"Unsupported pattern: ${pat.syntax}")

  def applyBindings(tree: Tree, bindings: Bindings)(using
      doc: SemanticDocument,
      matchOptions: MatchOptions
  ): Tree =
    tree.transform {
      case Term.ApplyType.After_4_6_0(bind, Type.ArgClause(substitutions))
          if substitutions.forall(isSubstitution) &&
            extractBinding(bind, bindings).isDefined =>
        val baseTree = extractBinding(bind, bindings).get
        applySubstitutions(baseTree, substitutions, bindings)
      case bind if extractBinding(bind, bindings).isDefined =>
        extractBinding(bind, bindings).get
    }

  def extractBinding(
      tree: Tree,
      bindings: Bindings
  ): Option[Tree] =
    tree match
      case Term.Apply.After_4_6_0(
            Term.Name("?"),
            Term.ArgClause(List(Term.Block(List(Term.Name(name)))), _)
          ) =>
        bindings.terms.get(name) match
          case Some(t) => Some(t)
          case None =>
            throw new Exception(s"No binding found for name: $name")
      case Term.Name(name) if name.startsWith("?") =>
        bindings.terms.get(name.stripPrefix("?")) match
          case Some(t) => Some(t)
          case None =>
            throw new Exception(s"No binding found for name: $name")
      case Type.Name(name) if name.startsWith("?") =>
        bindings.types.get(name.stripPrefix("?")) match
          case Some(t) => Some(t)
          case None =>
            throw new Exception(s"No binding found for type name: $name")
      case _ => None

  def isSubstitution(tree: Tree): Boolean =
    tree match
      case Type.ApplyInfix(_, Type.Name("->"), _) => true
      case _                                      => false

  def applySubstitutions(
      tree: Tree,
      substitutions: List[Tree],
      bindings: Bindings
  )(using doc: SemanticDocument): Tree =
    substitutions.foldLeft(tree) { (t, sub) =>
      sub match
        case Type.ApplyInfix(
              Type.Name(name),
              Type.Name("->"),
              Type.Name(substName)
            ) =>
          val baseTree = extractBinding(Term.Name(name), bindings)
          val subst = extractBinding(Term.Name(substName), bindings)
          (baseTree, subst) match
            case (Some(b), Some(s)) =>
              t.transform {
                case x if sameBinding(x, b) => s
              }
            case _ =>
              throw new Exception(
                s"Could not find bindings for substitution: ${sub.syntax}"
              )
        case _ =>
          throw new Exception(s"Unsupported substitution: ${sub.syntax}")
    }

  def collectTopLevelMatches(
      tree: Tree,
      f: Tree => Patch
  ): List[Patch] = {
    val buf = ListBuffer.empty[Patch]
    def visit(t: Tree): Unit = {
      f(t) match
        case p if !p.isEmpty => buf += p // don't recurse into children
        case _               => t.children.foreach(visit)
    }
    visit(tree)
    buf.toList
  }

  def getSymbolType(t: Tree)(using
      doc: SemanticDocument
  ): Option[SemanticType] =
    t.symbol.info match
      case Some(info) =>
        info.signature match
          case s: ValueSignature =>
            Some(s.tpe)
          case s: MethodSignature =>
            Some(s.returnType)
          case _ =>
            System.err.println(
              "Unsupported signature type for tree: " + t.syntax
            )
            None
      case _ =>
        System.err.println("No symbol info found for tree: " + t.syntax)
        None

  def matchTreeSemTypeWithAscription(
      cand: Tree,
      patType: Type,
      b: Bindings
  )(using doc: SemanticDocument): MatchResult =
    // Get the semanticdb type of candidate tree
    // Compare with the pattern type, considering bindings
    val candType = getSymbolType(cand)

    // No type info for candidate
    if (candType.isEmpty) then return None

    val patTrueType = patType match
      case Type.Name(name) if name.startsWith("?") =>
        b.types.get(name.stripPrefix("?")) match
          case Some(tpe) => tpe
          case None      =>
            // No previous binding
            // Creating new one here is illegal (binding semantic info)
            System.err.println(
              s"Cannot bind $name to implicit type variable: no explicit type ascription found"
            )
            return None
      case _ => patType

    (candType.get, patTrueType) match
      case (TypeRef(_, candSymbol, Nil), Type.Name(patTypeName)) =>
        if (candSymbol.displayName == patTypeName) then Some(b)
        else None
      case _ =>
        System.err.println(
          s"Unsupported type comparison between candidate type ${candType.get} " +
            s"and pattern type ${patType} (${patTrueType})"
        )
        None
