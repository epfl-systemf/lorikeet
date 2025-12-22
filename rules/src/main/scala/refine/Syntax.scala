package refine

import scala.meta._

/** Custom extractors for pattern matching on Scala AST nodes.
  *
  * These extractors represent special syntax used for rule pattern.
  */
object Syntax:

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

  /** Symbol binding for term or types: `?name` */
  object SymbolBind:
    def unapply(tree: Tree): Option[String] = tree match
      case Term.Name(name) if name.startsWith("?") =>
        Some(name.stripPrefix("?"))
      case Type.Name(name) if name.startsWith("?") =>
        Some(name.stripPrefix("?"))
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
    * fieldName is the name of the ascription/type field to skip during
    * structural comparison.
    */
  object WithOptionalType:
    def unapply(tree: Tree): Option[(Tree, Option[Type], String)] = tree match
      case d @ Defn.Def.After_4_7_3(_, _, _, decltpe, _) =>
        Some((d, decltpe, "decltpe"))
      case v @ Defn.Val(_, _, decltpe, _) =>
        Some((v, decltpe, "decltpe"))
      case v @ Defn.Var.After_4_7_2(_, _, decltpe, _) =>
        Some((v, decltpe, "decltpe"))
      case a @ Term.Ascribe(_, tpe) =>
        Some((a, Some(tpe), "tpe"))
      case _ => None

  /** Extracts parameter clause and body from Term.Function for parameter type
    * handling (as above)
    */
  object FunctionWithOptionalParamTypes:
    def unapply(tree: Tree): Option[(Term.ParamClause, Tree)] = tree match
      case Term.Function.After_4_6_0(pc @ Term.ParamClause(_, _), body) =>
        Some((pc, body))
      case _ => None

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

    /** Strip any unessary parts (AnonymousFunction wrapper when a wildcard is
      * present in the pattern...)
      */
    private def strip(tree: Tree): Tree = tree match
      case Term.AnonymousFunction(t) => t
      case _                         => tree
