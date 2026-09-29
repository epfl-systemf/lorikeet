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
    rewriteSource: Option[String],
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

  private def historyDirectory: Option[String] =
    sys.props.get("lorikeet.history.dir").orElse(sys.env.get("LORIKEET_HISTORY_DIR"))

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

  private case class FinalLint(
      rule: String,
      description: String,
      start: Int,
      end: Int,
      line: Int,
      column: Int,
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
          .filter(_.pos.start >= 0)
          .map(token => (token.pos.start, token.pos.end))
          .toSet
        val tokenStarts = tokenRanges.map(_._1)
        val tokenEnds = tokenRanges.map(_._2)
        rule.pattern
          .findAllMatchIn(sourceCode)
          .zipWithIndex
          .collect {
            case (matched, index)
                if tokenStarts(matched.start) && tokenEnds(matched.end) =>
              (matched.start, matched.end, matched.matched, index)
          }
          .toList

    val initialCode = doc.input.text
    val filename = inputName(doc.input)
    var code = initialCode
    var tree = doc.tree
    var steps = List.empty[RewriteStep]

    // Scalafix callers without history output still expect diagnostics on the
    // original document; generated feedback instead uses final history lints.
    val legacyLints =
      if historyDirectory.nonEmpty then Patch.empty
      else
        val treeLints = collectTopLevelMatches(doc.tree, { case candidate =>
          ruleTrees.flatMap { rule =>
            val enabled = rule.rewrite.isEmpty || lintLevel == LintLevel.Full
            Option.when(enabled && lintLevel != LintLevel.None) {
              val matcher = Matcher()(using doc, rule.matchOptions)
              Option.when(
                matcher.compare(rule.pattern, candidate, Bindings.empty).nonEmpty
              )(Patch.lint(LintMessage(candidate.pos, rule.name, rule.description)))
            }.flatten
          }.headOption.getOrElse(Patch.empty)
        })
        val tokenLints = tokenRules.flatMap { rule =>
          val enabled = rule.rewrite.isEmpty || lintLevel == LintLevel.Full
          if enabled && lintLevel != LintLevel.None then
            tokenMatches(rule, initialCode, doc.tree).map {
              case (start, end, _, _) =>
                Patch.lint(LintMessage(
                  Position.Range(doc.input, start, end),
                  rule.name,
                  rule.description
                ))
            }
          else Nil
        }
        (treeLints ++ tokenLints).asPatch

    def finalLints(): List[FinalLint] =
      if lintLevel == LintLevel.None then Nil
      else
        val lines = code.linesIterator.toVector
        def record(
            rule: String,
            description: Option[String],
            start: Int,
            end: Int
        ): FinalLint =
          val prefix = code.take(start)
          val line = prefix.count(_ == '\n') + 1
          FinalLint(
            rule,
            description.getOrElse(""),
            start,
            end,
            line,
            start - prefix.lastIndexOf('\n'),
            lines.lift(line - 1).getOrElse("")
          )

        val treeLints = ruleTrees
          .filter(rule => rule.rewrite.isEmpty || lintLevel == LintLevel.Full)
          .flatMap { rule =>
            val matcher = Matcher()(using doc, rule.matchOptions)
            tree.collect {
              case candidate
                  if candidate.pos.start >= 0 && matcher
                    .compare(rule.pattern, candidate, Bindings.empty)
                    .nonEmpty =>
                record(
                  rule.name,
                  rule.description,
                  candidate.pos.start,
                  candidate.pos.end
                )
            }
          }
        val tokenLints = tokenRules
          .filter(rule => rule.rewrite.isEmpty || lintLevel == LintLevel.Full)
          .flatMap(rule =>
            tokenMatches(rule, code, tree).map { case (start, end, _, _) =>
              record(rule.name, rule.description, start, end)
            }
          )
        (treeLints ++ tokenLints)
          .distinctBy(lint => (lint.rule, lint.start, lint.end))
          .sortBy(lint => (lint.start, lint.end, lint.rule))

    // A contextual rule matches the whole block to prove that an if result is
    // discarded. Only remove the dead suffix; reprinting the block adds braces
    // and converts Scala 3 control syntax to the pretty-printer's older syntax.
    def discardedBooleanEdit(original: Tree, rewritten: Tree): Option[(Int, Int)] =
      (original, rewritten) match
        case (Term.Block(oldStats), Term.Block(newStats))
            if oldStats.size == newStats.size =>
          val changed = oldStats.zip(newStats).filter { (oldStat, newStat) =>
            oldStat.structure != newStat.structure
          }
          changed match
            case List((oldIf: Term.If, newIf: Term.If))
                if oldIf.cond.structure == newIf.cond.structure &&
                  (oldIf.elsep match
                    case Lit.Boolean(false) => true
                    case _ => false) &&
                  newIf.elsep.isInstanceOf[Lit.Unit] =>
              val cut =
                if oldIf.thenp.structure == newIf.thenp.structure then
                  Some((oldIf.thenp.pos.end, false))
                else (oldIf.thenp, newIf.thenp) match
                  case (Term.Block(oldBody), Term.Block(newBody))
                      if oldBody.size >= 2 &&
                        (oldBody.last match
                          case Lit.Boolean(true) => true
                          case _ => false) &&
                        oldBody.dropRight(1).map(_.structure) ==
                          newBody.map(_.structure) =>
                    Some((oldBody(oldBody.size - 2).pos.end, true))
                  case _ => None
              cut.flatMap { (start, removedTrue) =>
                val end = oldIf.pos.end
                val removed = code.substring(start, end)
                val expected =
                  if removedTrue then "(?s)\\s+true\\s+else\\s+false"
                  else "(?s)\\s+else\\s+false"
                Option.when(removed.matches(expected))((start, end))
              }
            case _ => None
        case _ => None

    // Render simple rewrite templates with the matched source text, not with
    // scala.meta's Scala 2-style tree printer. Validate the resulting tree so
    // precedence-sensitive substitutions cannot change the rule's meaning.
    // ponytail: @mult and --> still use the tree printer; render their bound
    // source only if those rules show style churn in generated feedback.
    def sourceRewrite(
        rule: CustomRule,
        bindings: Bindings,
        candidate: Tree,
        rewritten: Tree
    ): Option[String] =
      val variable = "`\\?([A-Za-z][A-Za-z0-9_]*)`".r
      rule.rewriteSource.filterNot(text => text.contains("@mult") || text.contains("-->"))
        .flatMap { template =>
          val lines = template.linesIterator.toList.dropWhile(_.trim.isEmpty).reverse
            .dropWhile(_.trim.isEmpty).reverse
          val indent = lines.map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
          val normalized = lines.map(_.drop(indent)).mkString("\n")
          def render(parenthesize: Boolean): Option[String] =
            val output = new StringBuilder
            var previous = 0
            var complete = true
            variable.findAllMatchIn(normalized).foreach { matched =>
              val value = bindings.bindings.get(matched.group(1)).flatMap {
                case Binding.TermValue(term) => Some(term)
                case Binding.TypeValue(tpe) => Some(tpe)
                case _ => None
              }.filter(t => t.pos.start >= 0 && t.pos.end <= code.length)
              value match
                case Some(t) =>
                  output.append(normalized.substring(previous, matched.start))
                  val source = code.substring(t.pos.start, t.pos.end)
                  output.append(if parenthesize && t.isInstanceOf[Term] then
                    s"($source)" else source)
                  previous = matched.end
                case None => complete = false
            }
            if !complete then None
            else
              output.append(normalized.substring(previous))
              val lineStart = code.lastIndexOf('\n', candidate.pos.start - 1) + 1
              val leading = code.substring(lineStart, candidate.pos.start)
              val continuation = if leading.forall(_ == ' ') then leading else ""
              val rendered = output.toString.trim.linesIterator
                .mkString("\n" + continuation)
              scala.util.Try(Config.parseCode(rendered, rule.name, "rendered rewrite"))
                .toOption.filter(_.structure == rewritten.structure).flatMap { _ =>
                  def wholeSource(replacement: String): Option[Tree] =
                    val updated = code.substring(0, candidate.pos.start) +
                      replacement + code.substring(candidate.pos.end)
                    scala.util.Try(Config.parseSource(updated, filename)).toOption
                  val printed = wholeSource(rewritten.syntax)
                  Option.when(printed.exists(expected => wholeSource(rendered)
                    .exists(_.structure == expected.structure)))(rendered)
                }
          render(false).orElse(render(true))
        }

    def findRewrites(): List[RewriteStep] =
      val treeRewrites = ruleTrees.zipWithIndex
        .filter(_._1.rewrite.nonEmpty)
        .flatMap { case (rule, ruleOrder) =>
          val matcher = Matcher()(using doc, rule.matchOptions)
          val rewriter = Rewriter()(using doc, rule.matchOptions)
          tree
            .collect {
              case candidate
                  if candidate.pos.start >= 0 &&
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
                  val replacementTree = rewriter.applyBindings(rule.rewrite.get, bindings)
                  if candidate.structure == replacementTree.structure then None
                  else
                    val local = discardedBooleanEdit(candidate, replacementTree)
                    val edit = local match
                      case Some((from, to)) => Some((from, to, ""))
                      // These rules need the local edit; falling back to a
                      // whole-block print would reintroduce syntax churn.
                      case None if rule.name == "Discarded Boolean Result" ||
                          rule.name == "Discarded False Else Branch" => None
                      case None => Some((
                          candidate.pos.start,
                          candidate.pos.end,
                          sourceRewrite(rule, bindings, candidate, replacementTree)
                            .getOrElse(replacementTree.syntax)
                        ))
                    edit.flatMap { (start, end, replacement) =>
                      val before = code.substring(start, end)
                      Option.when(before != replacement)(
                        RewriteStep(
                          rule.name,
                          rule.description,
                          ruleOrder,
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
          writeHistory(initialCode, steps, finalLints(), parsedConfig.maxRewrites, false)
          return legacyLints +
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
    writeHistory(initialCode, steps, finalLints(), parsedConfig.maxRewrites, truncated)
    if truncated then
      throw IllegalStateException(
        s"Lorikeet stopped after ${parsedConfig.maxRewrites} rewrites in $filename."
      )
    legacyLints + Patch.replaceTree(doc.tree, code)

  private def writeHistory(
      initialCode: String,
      steps: List[RewriteStep],
      lints: List[FinalLint],
      limit: Int,
      truncated: Boolean
  )(using doc: SemanticDocument): Unit =
    historyDirectory.foreach { directory =>
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
            .mkString(",")}],"lints":[${lints
            .map { lint =>
              s"""{"rule":${json(lint.rule)},"description":${json(
                  lint.description
                )},"start":${lint.start},"end":${lint.end},"line":${lint.line},"column":${lint.column},"code":${json(lint.code)}}"""
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
    case file: scala.meta.inputs.Input.File   => file.path.syntax
    case file: scala.meta.inputs.Input.VirtualFile => file.path
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
