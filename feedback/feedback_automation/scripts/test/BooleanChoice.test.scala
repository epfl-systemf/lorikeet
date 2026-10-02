//> using file Check.scala
//> using test.dep org.scalameta::munit:1.3.6

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

class BooleanChoiceTest extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(180, "seconds")

  test("Boolean choice rewrites only stable names and observes strict OR") {
    val root = Files.createTempDirectory("boolean-choice-")
    val scaffold = Paths.get("scaffold_projects/find").toAbsolutePath.normalize()
    val lab = root.resolve("lab")
    val student = root.resolve("students/student-0")
    val relative = Paths.get("src/main/scala/find/find.scala")
    def copy(relative: Path): Unit =
      val target = lab.resolve(relative)
      Files.createDirectories(target.getParent)
      Files.copy(scaffold.resolve(relative), target)
    Seq("build.sbt", ".scalafmt.conf", ".lorikeet.conf",
      "project/plugins.sbt", "project/build.properties",
      "src/main/scala/find/cs214/Entry.scala").map(Paths.get(_)).foreach(copy)
    Files.createDirectories(student.resolve(relative).getParent)
    Files.writeString(student.resolve(relative),
      """package find
        |object Examples:
        |  def parameter(a: Boolean, b: Boolean, c: Boolean): Boolean =
        |    (!a && b) || (a && c)
        |  def local(a: Boolean, b: Boolean, c: Boolean): Boolean =
        |    val flag = a
        |    (!flag && b) || (flag && c)
        |  def mutable(b: Boolean, c: Boolean): Boolean =
        |    var flag = false
        |    (!flag && b) || (flag && c)
        |  def recomputed(b: Boolean, c: Boolean): Boolean =
        |    def flag: Boolean = b
        |    (!flag && b) || (flag && c)
        |  def strict(a: Boolean, b: Boolean): Boolean = a | b
        |  def bitwise(a: Int, b: Int): Int = a | b
        |""".stripMargin, UTF_8)
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
      cfg.historyDir.resolve("student-0.history.json"), UTF_8))("files")(0)
    val rewrites = history("steps").arr.filter(_("rule").str == "Choose by stable Boolean")
    assertEquals(rewrites.size, 2)
    val code = history("steps").arr.last("code").str
    assert(code.contains("if a then c else b"))
    assert(code.contains("if flag then c else b"))
    assert(code.contains("(!flag && b) || (flag && c)"))
    assertEquals(history("lints").arr.count(_("rule").str == "Strict Boolean |"), 1)
    assertEquals(history("lints").arr.count(_("rule").str == "Choose by stable Boolean"), 0)
  }
