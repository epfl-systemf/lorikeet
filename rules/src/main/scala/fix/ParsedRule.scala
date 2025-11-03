package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.collection.mutable.ListBuffer
import scala.io.Source._
import scala.util.{Try, Success, Failure}
import pureconfig._
import pureconfig.generic.derivation.default._
import pureconfig.error.ConfigReaderFailures

case class RuleConfig(
    name: String,
    pattern: String,
    rewrite: Option[String]
) derives ConfigReader
case class RulesConfig(rules: List[RuleConfig]) derives ConfigReader
case class Rule(
    name: String,
    pattern: Tree,
    rewrite: Option[Tree]
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
      types: Map[String, Tree]
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
      Rule(rule.name, matchTree, rewriteTree)
    }

    ruleTrees

  override def fix(implicit doc: SemanticDocument): Patch =

    val ruleTrees = parseRulesConfig()

    val result = collectTopLevelMatches(
      doc.tree,
      { case t =>
        ruleTrees
          .flatMap { case Rule(n, p, r) =>
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
      pattern: Tree,
      candidate: Tree,
      bindings: Bindings
  )(using doc: SemanticDocument): MatchResult =
    pattern match
      // Special handling for particular constructs
      case Term.Apply(Term.Name("?"), List(Term.Block(List(arg)))) =>
        matchWithPattern(arg, candidate, bindings)
      case Term.AnonymousFunction(
            Term.Apply(Term.Name("?"), List(Term.Block(List(arg))))
          ) =>
        matchWithPattern(arg, candidate, bindings)
      // Wildcard + binding for symbols
      case Term.Name(name) if name.startsWith("?") =>
        bindings.checkAddTerm(name.stripPrefix("?"), candidate)
      case Type.Name(name) if name.startsWith("?") =>
        bindings.checkAddType(name.stripPrefix("?"), candidate)
      // General case
      case _ =>
        val prodStruc =
          pattern.productPrefix == candidate.productPrefix &&
            pattern.productArity == candidate.productArity

        if (prodStruc) then
          pattern.productIterator
            .zip(candidate.productIterator)
            .foldLeft[MatchResult](Some(bindings)) {
              case (None, _) => None
              case (Some(b), (p, c)) =>
                compareFields(p, c, b)
            }
        else None

  def compareFields(pat: Any, cand: Any, bindings: Bindings)(using
      doc: SemanticDocument
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
  )(using doc: SemanticDocument): MatchResult =
    pat match
      case Term.ApplyUnary(Term.Name("+"), arg) =>
        compareTrees(arg, candidate, bindings)
      case Term.ApplyInfix(a, Term.Name("|"), Nil, List(b: Tree)) =>
        matchWithPattern(a, candidate, bindings) match
          case s @ Some(_) => s
          case None        => matchWithPattern(b, candidate, bindings)
      case Term.Placeholder() => Some(bindings)
      case Term.AnonymousFunction(f) =>
        matchWithPattern(f, candidate, bindings)
      case Term.ApplyInfix(
            Term.Name(name),
            Term.Name(":="),
            Nil,
            List(v: Tree)
          ) =>
        matchWithPattern(v, candidate, bindings).flatMap { newBindings =>
          newBindings.checkAddTerm(name, candidate)
        }
      case _ =>
        throw new Exception(s"Unsupported pattern: ${pat.syntax}")

  def applyBindings(tree: Tree, bindings: Bindings)(using
      doc: SemanticDocument
  ): Tree =
    tree.transform {
      case Term.ApplyType(bind, substitutions)
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
      case Term.Apply(
            Term.Name("?"),
            List(Term.Block(List(Term.Name(name))))
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
