package parsedRule

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.io.Source._
import scala.util.{Try, Success, Failure}
import pureconfig._
import pureconfig.error.ConfigReaderFailures

case class MatchOptions(
    // Whether to match type ascriptions literally
    // or only compare the symbol type
    matchAscriptions: Boolean
)

case class CustomRule(
    name: String,
    pattern: Tree,
    rewrite: Option[Tree],
    matchOptions: MatchOptions,
    description: Option[String]
)

case class LintMessage(t: Tree, r: String, m: Option[String])
    extends Diagnostic {
  override def position: Position = t.pos
  override def message: String =
    m match
      case Some(msg) => s"[$r] $msg"
      case None      => s"[$r] Rule matched."
}

class ScalaRewrite extends SemanticRule("ParsedRule"):

  def collectTopLevelMatches(
      tree: Tree,
      f: Tree => Patch
  ): List[Patch] =
    def visit(t: Tree): List[Patch] = {
      f(t) match
        case p if !p.isEmpty => List(p) // don't recurse into children
        case _               => t.children.flatMap(visit)
    }
    visit(tree)

  override def fix(implicit doc: SemanticDocument): Patch =
    val lintLevel = Config.getLintLevel()
    val ruleTrees = Config.parseRulesConfig()

    val result = collectTopLevelMatches(
      doc.tree,
      { case t =>
        ruleTrees
          .flatMap { case CustomRule(n, p, r, mo, lm) =>
            val matcher = Matcher()(using doc, mo)
            val rewriter = Rewriter()(using doc, mo)
            matcher.compareTrees(p, t, Matcher.Bindings.empty).map { bindings =>
              r match
                case None =>
                  // Lint only
                  if lintLevel == LintLevel.None then Patch.empty
                  else Patch.lint(LintMessage(t, n, lm))
                case Some(r) =>
                  // Rewrite
                  val rewrittenTree = rewriter.applyBindings(r, bindings)
                  Patch.replaceTree(t, rewrittenTree.syntax) +
                    (if lintLevel == LintLevel.Full
                     then Patch.lint(LintMessage(t, n, lm))
                     else Patch.empty)
            }
          }
          .headOption
          .getOrElse(Patch.empty)
      }
    )

    result.asPatch
