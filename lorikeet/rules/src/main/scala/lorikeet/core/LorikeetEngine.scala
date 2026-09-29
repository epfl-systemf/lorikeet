package lorikeet.core

import scalafix.v1._
import scala.meta._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.security.MessageDigest
import scala.util.matching.Regex

case class MatchOptions(
    // Whether to match type ascriptions literally
    // or only compare the symbol type
    matchAscriptions: Boolean,
    // Whether fully qualified names in patterns should be
    // interpreted as semantic symbol constraints
    matchFqn: Boolean,
    // Restrict matching to only these packages (if specified)
    onlyPackages: Option[List[String]],
    // Whether singleton blocks must match literally instead of transparently
    matchBlocks: Boolean = false
)

case class CustomRule(
    name: String,
    pattern: Tree,
    rewrite: Option[Tree],
    matchOptions: MatchOptions,
    description: Option[String]
)

case class TokenRule(
    name: String,
    pattern: Regex,
    rewrite: Option[String],
    onlyPackages: Option[List[String]],
    description: Option[String]
)

case class LintMessage(pos: Position, r: String, m: Option[String])
    extends Diagnostic {
  override def position: Position = pos
  override def message: String =
    m match
      case Some(msg) => s"[$r] $msg"
      case None      => s"[$r] Rule matched."
}

object LorikeetEngine:

  private case class RewriteStep(
      ruleName: String,
      description: Option[String],
      ruleOrder: Int,
      candidateOrder: Int,
      start: Int,
      end: Int,
      line: Int,
      column: Int,
      before: String,
      after: String,
      code: String
  )

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

  def run(config: Option[String])(using doc: SemanticDocument): Patch =
    val lintLevel = Config.getLintLevel()
    val parsedConfig = Config.parseConfig(config)
    val ruleTrees = parsedConfig.rules
    val tokenRules = parsedConfig.tokenRules

    def packageMatches(packages: Option[List[String]], source: Tree): Boolean =
      packages.forall { allowed =>
        val fullPackage =
          source.collect { case p: Pkg => p.ref.toString() }.mkString(".")
        allowed.exists(fullPackage.startsWith)
      }

    def tokenMatches(
        rule: TokenRule,
        sourceCode: String,
        source: Tree
    ): List[(Int, Int, String, Int)] =
      if !packageMatches(rule.onlyPackages, source) then Nil
      else
        val tokenRanges = source.tokens.iterator
          .filter(_.pos != Position.None)
          .map(token => (token.pos.start, token.pos.end))
          .toSet
        rule.pattern
          .findAllMatchIn(sourceCode)
          .zipWithIndex
          .collect {
            case (matched, index)
                if tokenRanges((matched.start, matched.end)) =>
              (matched.start, matched.end, matched.matched, index)
          }
          .toList

    val initialLints = collectTopLevelMatches(
      doc.tree,
      { case t =>
        ruleTrees
          .flatMap { case CustomRule(n, p, r, mo, lm) =>
            val matcher = Matcher()(using doc, mo)
            matcher.compare(p, t, Bindings.empty).map { _ =>
              r match
                case None =>
                  // Lint only
                  if lintLevel == LintLevel.None then Patch.empty
                  else Patch.lint(LintMessage(t.pos, n, lm))
                case Some(_) =>
                  if lintLevel == LintLevel.Full
                  then Patch.lint(LintMessage(t.pos, n, lm))
                  else Patch.empty
            }
          }
          .headOption
          .getOrElse(Patch.empty)
      }
    )

    val initialCode = doc.input.text
    val filename = inputName(doc.input)
    var code = initialCode
    var tree = doc.tree
    var steps = List.empty[RewriteStep]

    val initialTokenLints = tokenRules.flatMap { rule =>
      val lint = rule.rewrite match
        case None    => lintLevel != LintLevel.None
        case Some(_) => lintLevel == LintLevel.Full
      if lint then
        tokenMatches(rule, initialCode, doc.tree).map {
          case (start, end, _, _) =>
            Patch.lint(
              LintMessage(
                Position.Range(doc.input, start, end),
                rule.name,
                rule.description
              )
            )
        }
      else Nil
    }

    val lintPatch = (initialLints ++ initialTokenLints).asPatch

    def findRewrites(): List[RewriteStep] =
      val treeRewrites = ruleTrees.zipWithIndex
        .filter(_._1.rewrite.nonEmpty)
        .flatMap { case (rule, ruleOrder) =>
          val matcher = Matcher()(using doc, rule.matchOptions)
          val rewriter = Rewriter()(using doc, rule.matchOptions)
          tree
            .collect {
              case candidate
                  if candidate.pos != Position.None &&
                    (!rule.matchOptions.matchBlocks ||
                      !candidate.isInstanceOf[Term.Block] ||
                      (code(candidate.pos.start) == '{' &&
                        (candidate.pos.start == 0 ||
                          code(candidate.pos.start - 1) != '$'))) =>
                candidate
            }
            .zipWithIndex
            .flatMap { case (candidate, candidateOrder) =>
              matcher.compare(rule.pattern, candidate, Bindings.empty).flatMap {
                bindings =>
                  val replacement = rewriter
                    .applyBindings(rule.rewrite.get, bindings)
                    .syntax
                  val before =
                    code.substring(candidate.pos.start, candidate.pos.end)
                  Option.when(before != replacement)(
                    RewriteStep(
                      rule.name,
                      rule.description,
                      ruleOrder,
                      candidateOrder,
                      candidate.pos.start,
                      candidate.pos.end,
                      candidate.pos.startLine + 1,
                      candidate.pos.startColumn + 1,
                      before,
                      replacement,
                      ""
                    )
                  )
              }
            }
        }
        .toList
      val tokenRewrites = tokenRules.zipWithIndex.flatMap {
        case (rule, index) =>
          rule.rewrite.toList.flatMap { replacement =>
            tokenMatches(rule, code, tree).flatMap {
              case (start, end, before, candidateOrder) =>
                Option.when(before != replacement)(
                  RewriteStep(
                    rule.name,
                    rule.description,
                    ruleTrees.size + index,
                    candidateOrder,
                    start,
                    end,
                    0,
                    0,
                    before,
                    replacement,
                    ""
                  )
                )
            }
          }
      }
      treeRewrites ++ tokenRewrites

    def valid(step: RewriteStep): Boolean =
      step.start >= 0 && step.end <= code.length &&
        code.substring(step.start, step.end) == step.before

    def nextRewrite(pending: List[RewriteStep]): Option[RewriteStep] =
      (pending.filter(valid) ++ findRewrites())
        .distinctBy(step => (step.ruleOrder, step.start, step.end))
        .sortBy(step => (step.ruleOrder, step.start, step.candidateOrder))
        .headOption

    def afterEdit(
        pending: List[RewriteStep],
        applied: RewriteStep
    ): List[RewriteStep] =
      val shift = applied.after.length - (applied.end - applied.start)
      pending.flatMap { step =>
        if step.ruleOrder == applied.ruleOrder && step.start == applied.start &&
          step.end == applied.end
        then None
        else if step.end <= applied.start then Some(step)
        else if step.start >= applied.end then
          Some(step.copy(start = step.start + shift, end = step.end + shift))
        else None
      }

    var pending = findRewrites()
    while steps.size < parsedConfig.maxRewrites do
      nextRewrite(pending) match
        case None =>
          writeHistory(initialCode, steps, parsedConfig.maxRewrites, false)
          return lintPatch +
            Option
              .when(code != initialCode)(Patch.replaceTree(doc.tree, code))
              .getOrElse(Patch.empty)
        case Some(step) =>
          val prefix = code.substring(0, step.start)
          val lineStart = prefix.lastIndexOf('\n') + 1
          val applied = step.copy(
            line = prefix.count(_ == '\n') + 1,
            column = step.start - lineStart + 1
          )
          code = prefix + applied.after + code.substring(applied.end)
          pending = afterEdit(pending, applied)
          tree = Config.parseSource(code, filename)
          steps = steps :+ applied.copy(code = code)

    val truncated = nextRewrite(pending).nonEmpty
    writeHistory(initialCode, steps, parsedConfig.maxRewrites, truncated)
    if truncated then
      System.err.println(
        s"Lorikeet stopped after ${parsedConfig.maxRewrites} rewrites in $filename."
      )
    lintPatch + Patch.replaceTree(doc.tree, code)

  private def writeHistory(
      initialCode: String,
      steps: List[RewriteStep],
      limit: Int,
      truncated: Boolean
  )(using doc: SemanticDocument): Unit =
    sys.env.get("LORIKEET_HISTORY_DIR").foreach { directory =>
      val path = Paths.get(directory)
      Files.createDirectories(path)
      val file = inputName(doc.input)
      val id = MessageDigest
        .getInstance("SHA-256")
        .digest(file.getBytes(StandardCharsets.UTF_8))
        .take(12)
        .map("%02x".format(_))
        .mkString
      val history =
        s"""{"schemaVersion":1,"file":${json(
            file
          )},"limit":$limit,"truncated":$truncated,"initial":${json(
            initialCode
          )},"steps":[${steps
            .map { step =>
              s"""{"rule":${json(step.ruleName)},"description":${json(
                  step.description.getOrElse("")
                )},"start":${step.start},"end":${step.end},"line":${step.line},"column":${step.column},"before":${json(
                  step.before
                )},"after":${json(step.after)},"code":${json(step.code)}}"""
            }
            .mkString(",")}]}
           |""".stripMargin
      Files.writeString(
        path.resolve(s"$id.history.json"),
        history,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    }

  private def inputName(input: Input): String = input match
    case Input.File(path, _)                  => path.syntax
    case Input.VirtualFile(path, _)           => path
    case proxy: scala.meta.inputs.Input.Proxy => inputName(proxy.input)
    case _                                    => "source.scala"

  private def json(value: String): String =
    "\"" + value.flatMap {
      case '\"'         => "\\\""
      case '\\'         => "\\\\"
      case '\b'         => "\\b"
      case '\f'         => "\\f"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
