/** Common extractors and utilities for metasyntax trees used across both
  * pattern and rewrite metasyntax.
  */
package lorikeet.metasyntax.common

import scala.meta._

/** Metavariable `?name`
  *   - In pattern : Symbol binding for term or types
  *   - In rewrite : Identifier referencing a binding
  */
object MetaVar:
  def unapply(tree: Tree): Option[String] = tree match
    case Term.Name(name) if name.startsWith("?") && name != "?" =>
      Some(name.stripPrefix("?"))
    case Type.Name(name) if name.startsWith("?") && name != "?" =>
      Some(name.stripPrefix("?"))
    case _ => None

/** @mult annotation on parameters and arguments */
private[metasyntax] object MultAnnot:
  def unapply(tree: Tree): Boolean = tree match
    case Mod.Annot(Init(Type.Name("mult"), _, Seq())) => true
    case _                                            => false

object MultName:
  def unapply(tree: Tree): Option[String] = tree match
    case Term.Annotate(
          MetaVar(name),
          mods 
        ) if mods.exists(MultAnnot.unapply)=>
      Some(name)
    case _ => None

/** Base trait for @mult parameter extractors */
private[metasyntax] trait MultParamBase[T]:
  def transformName(name: Term.Name): T
  def transformType(tpe: Type.Name): T
  def unapply(tree: Tree): Option[(T, T)] =
    tree match
      case Term.Param(
            // @mult, but could have other annotations as well
            mods,
            // parameter name should be metavariable or wildcard
            paramName: Term.Name,
            // parameter type should be metavariable or wildcard
            Some(decltpe: Type.Name),
            // no default value
            None
          ) if mods.exists(MultAnnot.unapply) =>
        Some(transformName(paramName), transformType(decltpe))
      case Term.Param(List(MultAnnot()), name, tpe, defv) =>
        throw new Exception(
          s"Invalid @mult parameter: (${tree}). Expected wildcard/metavariable for both name and type, and no default value."
        )
      case _ => None

/** Strip any unessary parts (AnonymousFunction wrapper when a wildcard is
  * present in the pattern...)
  */
private[metasyntax] def strip(tree: Tree): Tree = tree match
  case Term.AnonymousFunction(t) => t
  case _                         => tree
