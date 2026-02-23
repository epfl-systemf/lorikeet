/** Extractors for rewrite metasyntax trees, which are used in the substitution
  * part of rewrite rules. These include:
  *   - Substitution syntax: `?name --> substitution`
  *   - Identifier referencing a binding: `?name`
  *   - `@mult` parameter extractors for rewrite templates
  */
package lorikeet.metasyntax.rewrite

import scala.meta._
import lorikeet.metasyntax.common
import lorikeet.metasyntax.common._

/** Substitution syntax: `?name --> substitution` */
object Substitution:
  def unapply(tree: Tree): Option[(String, Tree)] = tree match
    case Term.ApplyInfix.After_4_6_0(
          Term.Name(name),
          Term.Name("-->"),
          _,
          Term.ArgClause(List(subst), _)
        ) =>
      Some((name, subst))
    case Term.AnonymousFunction(
          Term.ApplyInfix.After_4_6_0(
            Term.Name(name),
            Term.Name("-->"),
            _,
            Term.ArgClause(List(subst), _)
          )
        ) =>
      Some((name, subst))
    case _ => None

object ParamMult extends common.ParamMult:
  def transformName(name: Term.Name): Option[String] =
    name match
      case MetaVar(n) => Some(n)
      case _ =>
        throw new Exception(
          s"Invalid @mult parameter name: ${name}. Expected a metavariable name."
        )

  def transformType(tpe: Type.Name): Option[String] =
    tpe match
      case MetaVar(t) => Some(t)
      case _ =>
        throw new Exception(
          s"Invalid @mult parameter type: ${tpe}. Expected a metavariable type."
        )
