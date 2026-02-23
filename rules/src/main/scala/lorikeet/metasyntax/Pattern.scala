/** Extractors for special syntax in patterns, such as wildcard symbols, pattern
  * blocks, and special constructs within pattern blocks (alternative patterns,
  * binding patterns, etc.)
  */
package lorikeet.metasyntax.pattern

import scala.meta._
import lorikeet.metasyntax.common
import lorikeet.metasyntax.common._

/** Wildcard symbol `?` */
object WildcardSymbol:
  def unapply(tree: Tree): Boolean = tree match
    case Term.Name(name) if name == "?" => true
    case Type.Name(name) if name == "?" => true
    case _                              => false

/** Pattern Blocks: `?{ pattern }` */
object PatternBlock:
  def unapply(tree: Tree): Option[Tree] = tree match
    case Term.Apply.After_4_6_0(
          Term.Name("?"),
          Term.ArgClause(List(Term.Block(List(arg))), _)
        ) =>
      Some(arg)
    case Term.AnonymousFunction(
          Term.Apply.After_4_6_0(
            Term.Name("?"),
            Term.ArgClause(List(Term.Block(List(arg))), _)
          )
        ) =>
      Some(arg)
    case _ => None

/** Extracts optional type ascriptions
  *
  * Returns (tree, optionalType, fieldName)
  *
  * optionalType is the optional ascription:
  *   - `def foo: Type = ...`
  *   - `val x: Type = ...`
  *   - `var y: Type = ...`
  *   - `expr: Type`
  *
  * fieldName is the name of the ascription/type field to skip during structural
  * comparison.
  */
object WithOptionalType:
  def unapply(tree: Tree): Option[(Tree, Option[Type], Option[String])] =
    tree match
      case d @ Defn.Def.After_4_7_3(_, _, _, decltpe, _) =>
        Some((d, decltpe, Some("decltpe")))
      case v @ Defn.Val(_, _, decltpe, _) =>
        Some((v, decltpe, Some("decltpe")))
      case v @ Defn.Var.After_4_7_2(_, _, decltpe, _) =>
        Some((v, decltpe, Some("decltpe")))
      case a @ Term.Ascribe(t, tpe) =>
        Some((t, Some(tpe), None))
      case _ => None

/** Extracts a tree with optional type ascription (as above). Additionally, for
  * terms without type ascription, returns an empty fieldName.
  */
object WithOrWithoutOptionalType:
  def unapply(tree: Tree): Option[(Tree, Option[Type], Option[String])] =
    tree match
      case WithOptionalType(base, optType, fieldName) =>
        Some((base, optType, fieldName))
      // Any other Term is just without type ascription
      case t: Term =>
        Some((t, None, None))
      case _ => None

/** Extracts parameter clause and body from Term.Function for parameter type
  * handling (as above)
  */
object FunctionWithOptionalParamTypes:
  def unapply(tree: Tree): Option[(Term.ParamClause, Tree)] = tree match
    case Term.Function.After_4_6_0(pc @ Term.ParamClause(_, _), body) =>
      Some((pc, body))
    case _ => None

object ParamMult extends common.ParamMult:
  def transformName(name: Term.Name): Option[String] =
    name match
      case MetaVar(n)       => Some(n)
      case WildcardSymbol() => None
      case _ =>
        throw new Exception(
          s"Invalid @mult parameter name: ${name}. Expected a metavariable or wildcard name."
        )

  def transformType(tpe: Type.Name): Option[String] =
    tpe match
      case MetaVar(t)       => Some(t)
      case WildcardSymbol() => None
      case _ =>
        throw new Exception(
          s"Invalid @mult parameter type: ${tpe}. Expected a metavariable or wildcard type."
        )

/** Extractors for special syntax inside pattern blocks
  */
object InPattern:

  /** Escape pattern: `+exp` Used to match expressions literally within a
    * pattern
    */
  object Escape:
    def unapply(tree: Tree): Option[Tree] = strip(tree) match
      case Term.ApplyUnary(Term.Name("+"), expr) => Some(expr)
      case _                                     => None

  /** Alternative patterns: `a | b` */
  object Alternative:
    def unapply(tree: Tree): Option[(Tree, Tree)] = strip(tree) match
      case Term.ApplyInfix.After_4_6_0(
            a,
            Term.Name("|"),
            Type.ArgClause(Nil),
            Term.ArgClause(List(b), _)
          ) =>
        Some(a, b)
      case _ => None

  /** Wildcard patterns: `_` */
  object Wildcard:
    def unapply(tree: Tree): Boolean = strip(tree) match
      case Term.Placeholder() => true
      case _                  => false

  /** Binding patterns: `name := pattern` */
  object Binding:
    def unapply(tree: Tree): Option[(String, Tree)] = strip(tree) match
      case Term.ApplyInfix.After_4_6_0(
            Term.Name(name),
            Term.Name(":="),
            Type.ArgClause(Nil),
            Term.ArgClause(List(v), _)
          ) =>
        Some(name, v)
      case _ => None

  /** Including patterns: `pattern including (uses...)` */
  object Including:
    def unapply(tree: Tree): Option[(Tree, List[Term])] = strip(tree) match
      case Term.ApplyInfix.After_4_6_0(
            v,
            Term.Name("including"),
            Type.ArgClause(Nil),
            Term.ArgClause(uses, _)
          ) =>
        Some(v, uses)
      case _ => None
