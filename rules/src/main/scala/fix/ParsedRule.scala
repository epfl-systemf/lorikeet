package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.collection.mutable.ListBuffer
import scala.io.Source._
import upickle.default._
import scala.util.{Try, Success, Failure}

case class RuleConfig(
    name: String,
    pattern: String,
    rewrite: Option[String]
) derives ReadWriter

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

  type Bindings = Map[String, Tree]
  type MatchResult = Option[Bindings]

  def parseRulesConfig(): List[Rule] =
    val configFile = sys.env.get("RULES_CONF") match
      case None           => ".rules.json"
      case Some(filename) => filename

    val rules: List[RuleConfig] = Try {
      read[List[RuleConfig]](fromFile(configFile).mkString)
    } match
      case Success(r) => r
      case Failure(e) =>
        throw new Exception(
          s"Could not read rules from configuration file: $configFile. " +
            s"Error: ${e.getMessage}"
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
            compareTrees(p, t, Map.empty[String, Tree]).map { bindings =>
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
  ): MatchResult =
    pattern match
      // Special handling for particular constructs
      case Term.Apply(Term.Name("?"), List(Term.Block(List(arg)))) =>
        matchWithPattern(arg, candidate, bindings)
      case Term.AnonymousFunction(
            Term.Apply(Term.Name("?"), List(Term.Block(List(arg))))
          ) =>
        matchWithPattern(arg, candidate, bindings)
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
                compareFields(
                  p,
                  c,
                  b
                )
            }
        else None

  def compareFields(pat: Any, cand: Any, bindings: Bindings): MatchResult =
    (pat, cand) match
      // Trees
      case (p: Tree, c: Tree) => compareTrees(p, c, bindings)
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

  def matchWithPattern(
      pat: Tree,
      candidate: Tree,
      bindings: Bindings
  ): MatchResult =
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
            Term.Name("->"),
            Nil,
            List(v: Tree)
          ) =>
        matchWithPattern(v, candidate, bindings).map { newBindings =>
          newBindings.updated(name, candidate)
        }
      case _ =>
        throw new Exception(s"Unsupported pattern: ${pat.syntax}")

  def applyBindings(tree: Tree, bindings: Bindings): Tree =
    tree.transform {
      case Term.Apply(
            Term.Name("?"),
            List(Term.Block(List(Term.Name(name))))
          ) =>
        if bindings.contains(name) then bindings(name)
        else throw new Exception(s"No binding found for name: $name")
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
