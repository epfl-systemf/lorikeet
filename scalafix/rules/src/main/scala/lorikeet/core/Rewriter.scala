package lorikeet.core

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import lorikeet.core.metasyntax.common.*
import lorikeet.core.metasyntax.rewrite.*

case class Rewriter()(using
    doc: SemanticDocument,
    matchOptions: MatchOptions
):

  val semMatcher = SemanticMatcher()

  // Extractor for metavariables that should reference a simple term or type
  private object BoundVar:
    def unapply(tree: Tree)(using b: Bindings): Option[Tree] = tree match
      case MetaVar(name) =>
        tree match
          case t: Term => Some(b.getOrThrow[Term](name))
          case t: Type => Some(b.getOrThrow[Type](name))
      case _ => None

  def applyBindings(tree: Tree, bindings: Bindings): Tree =
    given Bindings = bindings
    tree.transform {
      // Substitutions
      case Term.Apply
            .After_4_6_0(BoundVar(base), Term.ArgClause(substitutions, _))
          if substitutions.forall(isSubstitution) =>
        applySubstitutions(base, substitutions, bindings)
      // Mult vars for parameter lists
      case Term.ParamClause(List(MultParam(name, tpe)), mod) =>
        val names = bindings.getOrThrow[List[Term.Name]](name)
        val types = bindings.getOrThrow[List[Type]](tpe)
        if names.size != types.size then
          throw new Exception(
            s"@mult parameter size mismatch: ${names.size} names but ${types.size} types."
          )
        else
          val params =
            names
              .zip(types)
              .map((n, t) => Term.Param(mod.toList, n, Some(t), None))
          Term.ParamClause(params, mod)
      // Mult vars for argument lists
      case Term.ArgClause(List(MultName(name)), mod) =>
        val args = bindings.getOrThrow[List[Term]](name)
        Term.ArgClause(args, mod)
      // Mult vars for statement blocks
      case Term.Block(List(MultName(name))) =>
        val stats = bindings.getOrThrow[List[Stat]](name)
        Term.Block(stats)
      // Bound variables
      case BoundVar(t) => t
    }

  def isSubstitution(tree: Tree): Boolean =
    tree match
      case Substitution(_, _)     => true
      case MultSubstitution(_, _) => true
      case _                      => false

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
    given Bindings = bindings
    substitution match
      case Substitution(name, subst) =>
        val substTree = applyBindings(subst, bindings)
        val bound = bindings.getOrThrow[Term](name)
        tree.transform {
          case x if semMatcher.compareTrees(x, bound) => substTree
        }
      case MultSubstitution(name, substName) =>
        val ogList = bindings.getOrThrow[List[Term]](name)
        val substList = bindings.getOrThrow[List[Term]](substName)
        if ogList.size != substList.size then
          throw new Exception(
            s"@mult substitution size mismatch: $name refers to ${ogList.size} terms but $substName provides ${substList.size} terms."
          )
        else
          tree.transform {
            case n if ogList.exists(t => semMatcher.compareTrees(t, n)) =>
              val index = ogList.indexWhere(t => semMatcher.compareTrees(t, n))
              substList(index)
          }
      case _ =>
        throw new Exception(s"Unsupported substitution: ${substitution.syntax}")
