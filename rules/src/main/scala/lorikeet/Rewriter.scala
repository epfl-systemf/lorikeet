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

  // Extractor for metavariables that should reference a simple term or type
  private object BoundVar:
    def unapply(tree: Tree)(using b: Bindings): Option[Tree] = tree match
      case MetaVar(name) =>
        tree match
          case t: Term => Some(b.getTermOrThrow(name))
          case t: Type => Some(b.getTypeOrThrow(name))
      case _ => None

  def applyBindings(tree: Tree, bindings: Bindings): Tree =
    given Bindings = bindings
    tree.transform {
      case Term.Apply
            .After_4_6_0(BoundVar(base), Term.ArgClause(substitutions, _))
          if substitutions.forall(isSubstitution) =>
        applySubstitutions(base, substitutions, bindings)

      case Term.ParamClause(List(MultParam(name, tpe)), None)
          if bindings.getMultiTerm(name).isDefined &&
            bindings.getMultiType(tpe).isDefined =>
        val names = bindings.getMultiTerm(name).get
        val types = bindings.getMultiType(tpe).get
        if names.size != types.size then
          throw new Exception(
            s"@mult parameter size mismatch: ${names.size} names but ${types.size} types."
          )
        else
          val params = names.zip(types).map {
            case (n: Term.Name, t) =>
              Term.Param(Nil, n, Some(t), None)
            case (n, _) =>
              throw new Exception(
                s"Invalid @mult parameter bindings: expected metavariable $name to resolve to a name but got ${n.syntax} instead"
              )
          }
          Term.ParamClause(params, None)

      case Term.ArgClause(List(MultName(name)), None)
          if bindings.getMultiTerm(name).isDefined =>
        val args = bindings.getMultiTerm(name).get
        Term.ArgClause(args, None)

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
        val bound = bindings.getTermOrThrow(name)
        tree.transform {
          case x if Bindings.isEquivalent(x, bound) => substTree
        }
      case MultSubstitution(name, substName) =>
        val ogList = bindings.getMultiTermOrThrow(name)
        val substList = bindings.getMultiTermOrThrow(substName)
        if ogList.size != substList.size then
          throw new Exception(
            s"@mult substitution size mismatch: $name refers to ${ogList.size} terms but $substName provides ${substList.size} terms."
          )
        else
          tree.transform {
            case n if ogList.exists(t => Bindings.isEquivalent(t, n)) =>
              val index = ogList.indexWhere(t => Bindings.isEquivalent(t, n))
              substList(index)
          }
      case _ =>
        throw new Exception(s"Unsupported substitution: ${substitution.syntax}")
