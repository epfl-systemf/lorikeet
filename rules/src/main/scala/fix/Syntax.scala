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
