//> using file Check.scala
//> using test.dep org.scalameta::munit:1.3.6

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

class ContextualBooleanRulesTest extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(240, "seconds")

  test("contextual Boolean rewrites accept a following val but not a final if") {
    val root = Files.createTempDirectory("contextual-boolean-rules-")
    val scaffold = Paths.get("scaffold_projects/find").toAbsolutePath.normalize()
    val lab = root.resolve("lab")
    val student = root.resolve("students/student-0")
    val relative = Paths.get("src/main/scala/find/find.scala")
    def write(path: Path, value: String): Unit =
      Files.createDirectories(path.getParent)
      Files.writeString(path, value, UTF_8)
    Seq("build.sbt", ".scalafmt.conf", ".lorikeet.conf",
      "project/plugins.sbt", "project/build.properties").foreach { name =>
      write(lab.resolve(name), Files.readString(scaffold.resolve(name), UTF_8))
    }
    val entry = Paths.get("src/main/scala/find/cs214/Entry.scala")
    write(lab.resolve(entry), Files.readString(scaffold.resolve(entry), UTF_8))
    write(student.resolve(relative),
      """package find
        |object Examples:
        |  val BadValue = 1
        |  def BadFunction(BadParam: Int): Int = BadParam
        |
        |  def predicateFirst(predicate: cs214.Entry => Boolean, entry: cs214.Entry): Boolean =
        |    predicate(entry)
        |  def entryFirst(entry: cs214.Entry, predicate: cs214.Entry => Boolean): Boolean =
        |    predicate(entry)
        |
        |  def methodPredicate(entry: cs214.Entry): Boolean = entry.isDirectory()
        |
        |  def useHigherOrder(entry: cs214.Entry): Int =
        |    val predicate: cs214.Entry => Boolean = e => e.isDirectory()
        |    val selected = List(entry).filter(predicate)
        |    val selectedAgain = List(entry).filter(methodPredicate)
        |    selected.map(e => e.path()).size + selectedAgain.size
        |
        |  def findEmptyAndPrint(entry: cs214.Entry): Boolean =
        |    if entry.isDirectory() && !entry.hasChildren() || !entry.isDirectory() && entry.size() == 0 then
        |      println(entry.path())
        |      true
        |    else false
        |    val inChildren =
        |      entry.isDirectory() && entry.hasChildren() && findEmptyAndPrint(entry.firstChild())
        |    inChildren
        |
        |  def findCurrentAndChildren(entry: cs214.Entry, name: String): Boolean =
        |    val foundInCurrent =
        |      if entry.name() == name then
        |        println(entry.path())
        |        true
        |      else false
        |    val foundInChildren =
        |      entry.isDirectory() && entry.hasChildren() && findCurrentAndChildren(entry.firstChild(), name)
        |    foundInCurrent || foundInChildren
        |
        |  def terminalIf(entry: cs214.Entry): Boolean =
        |    if entry.isDirectory() then
        |      println(entry.path())
        |      true
        |    else false
        |
        |  def sameBranches(entry: cs214.Entry): Unit =
        |    if entry.isDirectory() then println(entry.path())
        |    else println(entry.path())
        |
        |  def sameValue(counter: Array[Int]): Int =
        |    if { counter(0) += 1; counter(0) == 1 } then 7 else 7
        |""".stripMargin)
    val output = root.resolve("output")
    val cfg = CheckTool.Config(lab, student.getParent,
      output.resolve("diffs"), output.resolve("histories"),
      output.resolve("originals"), output.resolve("lints"),
      output.resolve("results.json"), output.resolve(".tmp"),
      Seq(lab.resolve(relative)))
    Seq(cfg.diffDir, cfg.historyDir, cfg.originalDir, cfg.lintDir,
      cfg.tmpDir).foreach(Files.createDirectories(_))
    assert(CheckTool.checkStudent(student, cfg).isInstanceOf[CheckTool.IssuesFound])
    val history = ujson.read(Files.readString(
      cfg.historyDir.resolve("student-0.history.json"), UTF_8))("files")(0)
    val steps = history("steps").arr
    val rules = steps.map(_("rule").str).toSet
    assert(rules.contains("Unused if result"), s"$rules in $root")
    assert(rules.contains("Extract effect from Boolean if"), s"$rules in $root")
    assert(rules.contains("Identical if branches"), s"$rules in $root")
    val lints = history("lints").arr.map(_("rule").str).toSet
    assert(Set("Uppercase parameter", "Uppercase function", "Uppercase val",
      "Predicate-first helper", "Entry-first helper")
      .subsetOf(lints), s"$lints in $root")
    assertEquals(history("lints").arr.count(_("rule").str == "Higher-order call"), 3)
    val code = steps.last("code").str
    val emptyBody = code.split("def findEmptyAndPrint", 2)(1)
      .split("def findCurrentAndChildren", 2)(0)
    assert(!emptyBody.contains("else false"), emptyBody)
    val currentBody = code.split("def findCurrentAndChildren", 2)(1)
      .split("def terminalIf", 2)(0)
    assert(currentBody.contains("val foundInCurrent = entry.name() == name"), currentBody)
    assert(currentBody.contains("if foundInCurrent then println(entry.path())"), currentBody)
    val terminalBody = code.split("def terminalIf", 2)(1).split("def sameBranches", 2)(0)
    assert(terminalBody.contains("else false"), terminalBody)
    val sameBody = code.split("def sameBranches", 2)(1).split("def sameValue", 2)(0)
    assertEquals(sameBody.split("println\\(entry.path\\(\\)\\)").length - 1, 1)
    assert(sameBody.contains("entry.isDirectory()"), sameBody)
    val valueBody = code.split("def sameValue", 2)(1)
    assertEquals(valueBody.split("counter\\(0\\) \\+= 1").length - 1, 1)
    assert(valueBody.contains("7"), valueBody)
  }
