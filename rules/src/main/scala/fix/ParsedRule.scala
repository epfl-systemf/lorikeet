package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.collection.mutable.ListBuffer

val rules: List[String] =
  List(
    """(?{_}).+(?{_})""",
    """(?{_}).-(?{_})""",
    """(?{_}).*(?{_})""",
    """(?{_})./(?{_})"""
  )

case class LintMessage(t: Tree, r: String) extends Diagnostic {
  override def position: Position = t.pos
  override def message: String =
    s"Instance of parsed rule '$r' found at : \n${t.syntax}"
}

class ParsedRule extends SemanticRule("ParsedRule"):
  override def fix(implicit doc: SemanticDocument): Patch =

    // Parse rules
    val ruleTrees = rules.map(_.parse[Stat].get)

    val result = collectTopLevelMatches(
      doc.tree,
      { case t =>
        ruleTrees.find(compareTrees(_, t)) match
          case Some(r) =>
            Patch.lint(LintMessage(t, r.syntax))
          case None =>
            Patch.empty
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
      case _ =>
        val prodStruc =
          pattern.productPrefix == candidate.productPrefix &&
            pattern.productArity == candidate.productArity
        prodStruc &&
        pattern.productIterator
          .zip(candidate.productIterator)
          .forall({ case (p, c) => compareFields(p, c) })

  def compareFields(pat: Any, cand: Any): Boolean =
    (pat, cand) match
      // Trees
      case (p: Tree, c: Tree) => compareTrees(p, c)
      // Iterables
      case (p: Iterable[_], c: Iterable[_]) =>
        if p.size == c.size then
          val checks = p.zip(c).map { case (pp, cp) => compareFields(pp, cp) }
          checks.forall(identity)
        else false
      // Other fields
      case _ => pat == cand

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
