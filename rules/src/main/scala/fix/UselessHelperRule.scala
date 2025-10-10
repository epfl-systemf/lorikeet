package fix

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.collection.mutable.ListBuffer
import metaconfig.Conf.Bool

class UselessHelperRule extends SemanticRule("UselessHelperRule"):

  override def fix(implicit doc: SemanticDocument): Patch =
    val result = doc.tree
      .collect({
        case outerFun @ Defn.Def(_, _, _, _, _, _) =>
          outerFun.body match
            case Term.Block(
                  List(
                    innerFun @ Defn.Def(_, _, _, _, _, _),
                    innerCall
                  )
                ) if outerFun.decltpe.structure == innerFun.decltpe.structure =>
              innerCall match
                case Term.Apply(
                      Term.Name(callName),
                      innerCallArgs
                    )
                    if callName == innerFun.name.value &&
                      outerFun.paramss.head
                        .zip(innerCallArgs)
                        .forall((param, arg) =>
                          // println(arg.structure)
                          // println(param.structure)
                          arg match
                            case Term.Name(name) =>
                              name == param.name.value
                            case _ => false
                        ) =>
                  Patch.replaceTree(
                    outerFun.body,
                    untabOnce(innerFun.body.syntax)
                  )
            case _ => Patch.empty
        case _ => Patch.empty
      })
      .asPatch
    result

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

  def untabOnce(s: String): String =
    s.linesIterator
      .map(line =>
        if line.startsWith("  ") then line.drop(2)
        else line
      )
      .mkString("\n")
