package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.collection.mutable.ListBuffer

val rule: String = """if ?{_} then ?{_} else ?{+true | +false}"""

case class LintMessage(t: Tree) extends Diagnostic {
  override def position: Position = t.pos
  override def message: String =
    s"Instance of parsed rule '$rule' found at : \n${t.syntax}"
}

class ParsedRule extends SemanticRule("ParsedRule"):
  override def fix(implicit doc: SemanticDocument): Patch =

    // Parse rule
    val ruleTree = rule.parse[Stat].get

    val result = collectTopLevelMatches(
      doc.tree,
      {
        case t if compareTrees(ruleTree, t) =>
          Patch.lint(LintMessage(t))
      }
    )

    result.asPatch

  def compareTrees(pattern: Tree, candidate: Tree): Boolean =
    pattern match
      // Special handling for particular constructs
      case Term.Apply(Term.Name("?"), List(Term.Block(List(arg)))) =>
        matchWithPattern(arg, candidate)
      case Term.AnonymousFunction(
            Term.Apply(Term.Name("?"), List(Term.Block(List(arg))))
          ) =>
        matchWithPattern(arg, candidate)
      case _: Lit => pattern.structure == candidate.structure
      case _ if pattern == candidate =>
        true
      case _ =>
        val prodPref = pattern.productPrefix == candidate.productPrefix
        if (prodPref) then
          val childrenCheck = pattern.children.zip(candidate.children).map {
            case (pChild, cChild) => compareTrees(pChild, cChild)
          }
          val result = prodPref && childrenCheck.forall(identity)
          result
        else false

  def matchWithPattern(pat: Tree, candidate: Tree): Boolean =
    pat match
      case Term.ApplyUnary(Term.Name("+"), arg) =>
        compareTrees(arg, candidate)
      case Term.ApplyInfix(a, Term.Name("|"), Nil, List(b: Tree)) =>
        val acheck = matchWithPattern(a, candidate)
        val bcheck = matchWithPattern(b, candidate)
        acheck || bcheck
      case Term.Placeholder() => true
      case Term.AnonymousFunction(f) =>
        matchWithPattern(f, candidate)
      case _ =>
        throw new Exception(s"Unsupported pattern: ${pat.syntax}")

  def collectTopLevelMatches[A](
      tree: Tree,
      f: PartialFunction[Tree, A]
  ): List[A] = {
    val buf = ListBuffer.empty[A]
    def visit(t: Tree): Unit = {
      if (f.isDefinedAt(t)) {
        buf += f(t) // don’t recurse into children
      } else {
        t.children.foreach(visit)
      }
    }
    visit(tree)
    buf.toList
  }
