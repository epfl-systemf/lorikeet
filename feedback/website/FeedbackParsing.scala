import scala.collection.mutable

final case class LintIssue(
    name: String,
    message: String,
    path: String,
    line: Int,
    column: Int,
    code: String,
    width: Int
)

final case class DiffBlock(start: Int, before: Seq[String], after: Seq[String])
object FeedbackParsing:
  private val LintHeading = raw"\[[^\]]+\]".r
  private val LintLocation = raw"(.+):(\d+):(\d+)".r
  private val Pointer = raw"\s*\^+".r
  private val Hunk = raw"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*".r

  def parseLint(text: String): Seq[LintIssue] =
    val lines = text.linesIterator.toVector
    val issues = mutable.ArrayBuffer.empty[LintIssue]
    var name: Option[String] = None
    var message: Option[String] = None
    var index = 0
    while index < lines.length do
      lines(index) match
        case LintHeading() =>
          name = Some(lines(index).drop(1).dropRight(1))
          index += 1
          if index >= lines.length then
            throw IllegalArgumentException(
              "Missing description after rule heading"
            )
          message = Some(
            lines(index).replaceFirst(raw"\s*\(\d+ occurrences\)$$", "").trim
          )
        case LintLocation(path, row, column) =>
          if name.isEmpty || index + 1 >= lines.length then
            throw IllegalArgumentException(
              "Issue location without rule or code"
            )
          val code = lines(index + 1)
          val pointer = lines.lift(index + 2).getOrElse("")
          val width =
            if Pointer.matches(pointer) then pointer.trim.length else 1
          issues += LintIssue(
            name.get,
            message.getOrElse(""),
            path,
            row.toInt,
            column.toInt,
            code,
            width
          )
          index += (if pointer.contains("^") then 2 else 1)
        case line if line.trim.nonEmpty =>
          throw IllegalArgumentException("Unexpected lint report line: " + line)
        case _ => ()
      index += 1
    issues.toSeq

  def parseDiff(text: String): (Map[Int, String], Seq[DiffBlock]) =
    val original = mutable.TreeMap.empty[Int, String]
    val blocks = mutable.ArrayBuffer.empty[DiffBlock]
    var oldLine: Option[Int] = None
    var newLine = 0
    var oldLeft = 0
    var newLeft = 0
    var blockStart = 0
    var before = mutable.ArrayBuffer.empty[String]
    var after = mutable.ArrayBuffer.empty[String]

    def flush(): Unit =
      if before.nonEmpty || after.nonEmpty then
        blocks += DiffBlock(blockStart, before.toSeq, after.toSeq)
        before = mutable.ArrayBuffer.empty
        after = mutable.ArrayBuffer.empty

    text.linesIterator.foreach {
      case Hunk(oldStart, oldCount, newStart, newCount) =>
        if oldLeft != 0 || newLeft != 0 then
          throw IllegalArgumentException("Truncated diff hunk")
        flush()
        oldLine = Some(oldStart.toInt)
        newLine = newStart.toInt
        oldLeft = Option(oldCount).fold(1)(_.toInt)
        newLeft = Option(newCount).fold(1)(_.toInt)
      case line if line.startsWith("\\ No newline")                  => ()
      case line if oldLine.isEmpty || (oldLeft == 0 && newLeft == 0) =>
        flush()
        if line.nonEmpty && !Seq("--- ", "+++ ", "diff ", "index ").exists(
            line.startsWith
          )
        then throw IllegalArgumentException("Unexpected diff content: " + line)
      case line if line.startsWith(" ") =>
        flush()
        original(oldLine.get) = line.drop(1)
        oldLine = Some(oldLine.get + 1)
        newLine += 1
        oldLeft -= 1
        newLeft -= 1
      case line if line.startsWith("-") || line.startsWith("+") =>
        if before.isEmpty && after.isEmpty then blockStart = oldLine.get
        if line.startsWith("-") then
          original(oldLine.get) = line.drop(1)
          before += line.drop(1)
          oldLine = Some(oldLine.get + 1)
          oldLeft -= 1
        else
          after += line.drop(1)
          newLine += 1
          newLeft -= 1
      case line =>
        throw IllegalArgumentException("Unexpected diff hunk line: " + line)
    }
    flush()
    while !text.endsWith("\n") && oldLeft == newLeft && oldLeft > 0 do
      original(oldLine.get) = ""
      oldLine = Some(oldLine.get + 1)
      newLine += 1
      oldLeft -= 1
      newLeft -= 1
    if oldLeft != 0 || newLeft != 0 then
      throw IllegalArgumentException("Truncated diff hunk")
    (original.toMap, blocks.toSeq)

