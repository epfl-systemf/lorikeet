package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.collection.mutable.ListBuffer
import metaconfig.Conf.Bool

class SimplIfRule extends SemanticRule("SimplIfRule"):

  override def fix(implicit doc: SemanticDocument): Patch =
    val result = collectTopLevelMatches(
      doc.tree,
      {
        case t @ Term.If(cond, thenb, Lit.Boolean(b)) =>
          Patch.replaceTree(t, simplifyIf(t))
        case t @ Term.If(cond, Lit.Boolean(b), elseb) =>
          Patch.replaceTree(t, simplifyIf(t))
      }
    )
    result.asPatch

  private def simplifyIf(tree: Tree): String =
    tree match {
      case Term.If(cond, thenb, Lit.Boolean(b)) =>
        if b then s"(!(${simplifyIf(cond)}) || ${simplifyIf(thenb)})"
        else s"(${simplifyIf(cond)} && ${simplifyIf(thenb)})"
      case Term.If(cond, Lit.Boolean(b), elseb) =>
        if b then s"(${simplifyIf(cond)} || ${{ simplifyIf(elseb) }})"
        else s"(!(${simplifyIf(cond)}) && ${simplifyIf(elseb)})"
      case Term.Block(stats) =>
        s"{ ${stats.map(simplifyIf).mkString("; ")} }"
      case _ => tree.syntax
    }

  def collectTopLevelMatches[A](
      tree: Tree,
      f: PartialFunction[Tree, A]
  ): List[A] = {
    val buf = ListBuffer.empty[A]
    def visit(t: Tree): Unit = {
      if (f.isDefinedAt(t)) {
        buf += f(t) // don’t recurse into children
      } else {
        t.children.foreach(visit)
      }
    }
    visit(tree)
    buf.toList
  }
