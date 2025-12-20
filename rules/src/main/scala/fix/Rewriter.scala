package parsedRule

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import parsedRule.Matcher.Bindings

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
      case Term.AnonymousFunction(sub) => isSubstitution(sub)
      case Term.ApplyInfix.After_4_6_0(_, Term.Name("->"), _, _) => true
      case _                                                     => false

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
      case Term.AnonymousFunction(sub) =>
        applySingleSubstitution(tree, sub, bindings)
      case Term.ApplyInfix.After_4_6_0(
            Term.Name(name),
            Term.Name("->"),
            _,
            Term.ArgClause(List(subst), _)
          ) =>
        val baseTree = extractBinding(Term.Name(name), bindings)
        val substTree = applyBindings(subst, bindings)
        baseTree match
          case Some(b) =>
            tree.transform {
              case x if Bindings.sameBinding(x, b) => subst
            }
          case _ =>
            throw new Exception(
              s"Could not find bindings for substitution: ${substitution.syntax}"
            )
      case _ =>
        throw new Exception(s"Unsupported substitution: ${substitution.syntax}")
