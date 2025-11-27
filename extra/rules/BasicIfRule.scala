package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3

class BasicIfRule extends SemanticRule("BasicIfRule") {

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t @ Term.If(cond, Lit.Boolean(true), Lit.Boolean(false)) =>
        Patch.replaceTree(t, cond.syntax)
    }.asPatch
}
