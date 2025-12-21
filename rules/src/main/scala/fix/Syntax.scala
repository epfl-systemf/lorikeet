package parsedRule

import scala.meta._

object Syntax:

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

  object SymbolBind:
    def unapply(tree: Tree): Option[String] = tree match
      case Term.Name(name) if name.startsWith("?") =>
        Some(name.stripPrefix("?"))
      case Type.Name(name) if name.startsWith("?") =>
        Some(name.stripPrefix("?"))
      case _ => None

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

  object FunctionWithOptionalParamTypes:
    def unapply(tree: Tree): Option[(Term.ParamClause, Tree)] = tree match
      case Term.Function.After_4_6_0(pc @ Term.ParamClause(_, _), body) =>
        Some((pc, body))
      case _ => None