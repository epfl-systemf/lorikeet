import os.Path
import scala.util.matching.Regex
import java.io.File

object ScalafixOutputProcessor:

  case class Rule(
      name: String,
      description: String
  )

  sealed trait LineType
  case class ErrorHeader(
      path: String,
      line: Int,
      col: Int,
      ruleName: String,
      message: String
  ) extends LineType
  case class CodeLine(code: String) extends LineType
  case class PointerLine(pointer: String) extends LineType
  case object OtherLine extends LineType

  private val ErrorHeaderPattern: Regex =
    """\[error\]\s+(\S+):(\d+):(\d+):\s+error:\s+\[MetaRule\]\s+\[(.*?)\]\s+(.*)""".r
  private val CodeLinePattern: Regex =
    """\[error\](\s*.*)""".r
  private val PointerLinePattern: Regex =
    """\[error\](\s*\^+)""".r

  private def getLineType(line: String, rootPrefix: String): LineType = {
    line.replace(rootPrefix, "") match {
      case ErrorHeaderPattern(path, lineNum, colNum, ruleName, message) =>
        ErrorHeader(path, lineNum.toInt, colNum.toInt, ruleName, message.trim)
      case PointerLinePattern(pointer) =>
        PointerLine(pointer)
      case CodeLinePattern(code) =>
        CodeLine(code)
      case _ =>
        OtherLine
    }
  }

  case class IssueDetail(
      ruleName: String,
      message: String,
      path: String,
      line: Int,
      col: Int,
      codeLine: String,
      pointerLine: String
  )

  def processLintReport(
      output: String,
      reportFile: Path,
      root: Path
  ): Seq[Rule] = {
    val rootPrefix = root.toString + File.separator
    val lines = output
      .replaceAll("\\e\\[[\\d;]*[^\\d;]", "") // Remove ANSI codes
      .split('\n')
      .toSeq
      .filter(_.startsWith("[error]"))
      .map(line => getLineType(line, rootPrefix))
      .zipWithIndex

    val issueBlock: Seq[IssueDetail] = lines.flatMap {
      case (header @ ErrorHeader(path, line, col, ruleName, message), i) =>
        val codeLine =
          lines
            .drop(i + 1)
            .headOption
            .collect { case (CodeLine(code), _) => code }
            .getOrElse("")
        val pointerLine =
          lines
            .drop(i + 2)
            .headOption
            .collect { case (PointerLine(pointer), _) => pointer }
            .getOrElse("")

        Some(
          IssueDetail(
            ruleName,
            message,
            path,
            line,
            col,
            codeLine,
            pointerLine
          )
        )
      case _ =>
        None
    }

    val foundRules =
      issueBlock.map(issue => Rule(issue.ruleName, issue.message))

    val report = issueBlock
      .groupBy( // rule name and message
        issue => (issue.ruleName, issue.message)
      )
      .map { case ((ruleName, message), issues) =>
        val reportBlock = new StringBuilder
        reportBlock.append(
          s"[${ruleName}]\n${message} (${issues.length} occurrences)\n\n"
        )
        issues.foreach { issue =>
          reportBlock.append(s"${issue.path}:${issue.line}:${issue.col}\n")
          reportBlock.append(s"${issue.codeLine}\n")
          if (issue.pointerLine.nonEmpty) {
            reportBlock.append(s"${issue.pointerLine}\n")
          }
        }
        reportBlock.toString()
      }
      .toSeq
      .mkString("\n")

    if (report.nonEmpty) then os.write(reportFile, report)
    else os.remove(reportFile)

    foundRules
  }
