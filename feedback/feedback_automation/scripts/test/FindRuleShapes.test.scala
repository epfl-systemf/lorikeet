//> using file Check.scala
//> using test.dep org.scalameta::munit:1.3.6

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

class FindRuleShapesTest extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(360, "seconds")

  test("find-lab control-flow rewrites keep compiling and preserve Scala 3 source") {
    val root = Files.createTempDirectory("find-rule-shapes-")
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
        |  lazy val first = 1
        |  private lazy val second = 2
        |  val BadValue = 3
        |  def BadName(value: Int): Int = value
        |
        |  def findFirstByNameAndPrint(entry: cs214.Entry, name: String): Boolean =
        |    if entry.name() == name then
        |      println(entry.path())
        |      return true
        |    if entry.isDirectory() && entry.hasChildren() && findFirstByNameAndPrint(entry.firstChild(), name) then
        |      return true
        |    if entry.hasNextSibling() && findFirstByNameAndPrint(entry.nextSibling(), name) then
        |      return true
        |    false
        |
        |  def findAllAndPrint(entry: cs214.Entry): Boolean =
        |    println(entry.path())
        |    if entry.isDirectory() && entry.hasChildren() then
        |      findAllAndPrint(entry.firstChild())
        |    if entry.hasNextSibling() then findAllAndPrint(entry.nextSibling())
        |    true
        |
        |  def alwaysTrue(entry: cs214.Entry): Boolean =
        |    findAllAndPrint(entry.firstChild()) || true
        |
        |  def alwaysTrueAfter(entry: cs214.Entry): Boolean =
        |    println(entry.path())
        |    findAllAndPrint(entry.firstChild()) || true
        |
        |  def emptyHere(entry: cs214.Entry): Boolean =
        |    val foundHere =
        |      if ((entry.isDirectory()) && !(entry.hasChildren())) || (!(entry.isDirectory()) && (entry.size() == 0)) then
        |        println(entry.path())
        |        true
        |      else false
        |    foundHere
        |
        |  def emptyWithRepeatedPrint(entry: cs214.Entry): Boolean =
        |    if entry.isDirectory() && !entry.hasChildren() then
        |      println(entry.path())
        |      true
        |    else if !entry.isDirectory() && entry.size() == 0 then
        |      println(entry.path())
        |      true
        |    else false
        |
        |  def emptyViaVal(entry: cs214.Entry): Boolean =
        |    val foundHere =
        |      if entry.isDirectory() && !entry.hasChildren() then
        |        println(entry.path())
        |        true
        |      else if !entry.isDirectory() && entry.size() == 0 then
        |        println(entry.path())
        |        true
        |      else false
        |    foundHere
        |
        |  def siblingThroughVal(entry: cs214.Entry, name: String): Boolean =
        |    val val1 = (findByNameAndPrint(entry.nextSibling(), name))
        |    val1
        |
        |  def multilineFinal(entry: cs214.Entry): Boolean =
        |    val result =
        |      if entry.hasNextSibling() then
        |        entry.path().nonEmpty
        |      else entry.isDirectory()
        |    result
        |
        |  def nestedChildren(entry: cs214.Entry, name: String): Boolean =
        |    if entry.isDirectory() then
        |      if entry.hasChildren() then
        |        findFirstByNameAndPrint(entry.firstChild(), name)
        |      else false
        |    else false
        |
        |  def tripleNestedChildren(entry: cs214.Entry, name: String): Boolean =
        |    if entry.isDirectory() then
        |      if entry.hasChildren() then
        |        if entry.hasNextSibling() then
        |          findFirstByNameAndPrint(entry.firstChild(), name)
        |        else false
        |      else false
        |    else false
        |
        |  class Weird:
        |    def &&(right: Boolean): String = "not Boolean"
        |
        |  def keepCustomAnd(entry: cs214.Entry, weird: Weird): Any =
        |    if entry.isDirectory() then weird && true else false
        |
        |  def keepTypedVal(value: Int): Any =
        |    val same: Any = value
        |    same
        |
        |  def findByNameAndPrint(entry: cs214.Entry, name: String): Boolean =
        |    var found = false
        |    if entry.name() == name then
        |      println(entry.path())
        |      found = true
        |    if entry.isDirectory() && entry.hasChildren() && findByNameAndPrint(entry.firstChild(), name) then
        |      found = true
        |    if entry.hasNextSibling() && findByNameAndPrint(entry.nextSibling(), name) then
        |      found = true
        |    found
        |
        |  def finalReturn(value: Int): Int =
        |    val doubled = value * 2
        |    return doubled
        |
        |  def finalReturnTwo(left: Int, right: Int): Int =
        |    val sum = left + right
        |    return sum
        |
        |  def yoda(entry: cs214.Entry, value: Int): Boolean =
        |    val size: Long = entry.size()
        |    (0 == size) && (1 != value)
        |
        |  def printIfMatching(entry: cs214.Entry, name: String): Boolean =
        |    val here =
        |      if name == entry.name() then
        |        println(entry.path())
        |        true
        |      else false
        |    here
        |
        |  def printIfEmpty(entry: cs214.Entry): Boolean =
        |    val here =
        |      if !entry.isDirectory() then
        |        if entry.size() == 0 then
        |          println(entry.path())
        |          true
        |        else false
        |      else if !entry.hasChildren() then
        |        println(entry.path())
        |        true
        |      else false
        |    here
        |""".stripMargin)
    val output = root.resolve("output")
    val cfg = CheckTool.Config(lab, student.getParent,
      output.resolve("diffs"), output.resolve("histories"),
      output.resolve("originals"), output.resolve("lints"),
      output.resolve("results.json"), output.resolve(".tmp"),
      Seq(lab.resolve(relative)))
    Seq(cfg.diffDir, cfg.historyDir, cfg.originalDir, cfg.lintDir,
      cfg.tmpDir).foreach(Files.createDirectories(_))
    val result = CheckTool.checkStudent(student, cfg)
    assert(result.isInstanceOf[CheckTool.IssuesFound], s"$result in $root")
    val history = ujson.read(Files.readString(
      cfg.historyDir.resolve("student-0.history.json"), UTF_8))
    val steps = history("files")(0)("steps").arr
    val lints = history("files")(0)("lints").arr
    assertEquals(lints.count(_("rule").str == "lazy val"), 2)
    assertEquals(lints.count(_("rule").str == "Mutable var"), 1)
    assertEquals(lints.count(_("rule").str == "Uppercase function"), 1)
    assertEquals(lints.count(_("rule").str == "Uppercase val"), 1)
    val rules = steps.map(_("rule").str).toSet
    assert(Set("Search with early returns", "Final return", "0 == Long", "1 != Int",
      "Evaluate before true", "Evaluate before true after statements",
      "Combine equal Boolean branches", "Inline final val",
      "Extract effect from Boolean if",
      "Decouple value and side-effect form nested ifs").subsetOf(rules), s"$rules in $root")
    val finalCode = steps.last("code").str
    assert(finalCode.contains("if here then println(entry.path())"))
    assert(finalCode.contains("var found"))
    assert(!finalCode.contains("|| true"))
    assert(!finalCode.contains("val val1 ="))
    assert(!finalCode.contains("val result ="))
    assert(finalCode.contains("def siblingThroughVal(entry: cs214.Entry, name: String): Boolean =\n    findByNameAndPrint(entry.nextSibling(), name)"))
    assert(finalCode.contains("if foundHere then println(entry.path())"))
    assert(!finalCode.contains("else if !entry.isDirectory() && entry.size() == 0 then"))
    assert(finalCode.contains("def nestedChildren(entry: cs214.Entry, name: String): Boolean =\n    entry.isDirectory() &&"))
    assert(finalCode.contains("def tripleNestedChildren(entry: cs214.Entry, name: String): Boolean =\n    entry.isDirectory() &&"))
    assert(finalCode.contains("if entry.isDirectory() then weird && true else false"))
    assert(finalCode.contains("val same: Any = value"))
    assert(finalCode.contains("val here = name == entry.name()"))
    assert(finalCode.contains("if !entry.isDirectory() then entry.size() == 0"))
    assert(!finalCode.contains("return doubled"))
    assert(!finalCode.contains("return sum"))
    assert(finalCode.contains("size == 0"))
    assert(finalCode.contains("value != 1"))
    assert(!finalCode.contains("if ("))
  }
