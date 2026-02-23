package lorikeet

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import lorikeet.metasyntax.common._
import lorikeet.metasyntax.rewrite._

case class Rewriter()(using
    doc: SemanticDocument,
    matchOptions: MatchOptions
):
  def applyBindings(tree: Tree, bindings: Bindings): Tree =
    tree.transform {
      case Term.Apply.After_4_6_0(bind, Term.ArgClause(substitutions, _))
          if substitutions.forall(isSubstitution) &&
            substitutions.nonEmpty &&
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
      case MetaVar(name) =>
        tree match
          case t: Term => Some(bindings.getTermOrThrow(name))
          case t: Type => Some(bindings.getTypeOrThrow(name))
      case _ => None

  def isSubstitution(tree: Tree): Boolean =
    tree match
      case Substitution(_, _) => true
      case _                                     => false

  def applySubstitutions(
      tree: Tree,
      substitutions: List[Tree],
      bindings: Bindings
  ): Tree =
    substitutions.foldLeft(tree) { (t, sub) =>
      applySingleSubstitution(t, sub, bindings)
    }

  def applySingleSubstitution(
      tree: Tree,
      substitution: Tree,
      bindings: Bindings
  ): Tree =
    substitution match
      case Substitution(name, subst) =>
        val baseTree = extractBinding(Term.Name(name), bindings)
        val substTree = applyBindings(subst, bindings)
        baseTree match
          case Some(b) =>
            tree.transform {
              case x if Bindings.isEquivalent(x, b) => subst
            }
          case _ =>
            throw new Exception(
              s"Could not find bindings for substitution: ${substitution.syntax}"
            )
      case _ =>
        throw new Exception(s"Unsupported substitution: ${substitution.syntax}")
