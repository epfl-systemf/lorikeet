//> using file Check.scala
//> using file ../../website/GenerateFeedback.scala
//> using file ../../website/FeedbackFiles.scala
//> using file ../../website/FeedbackReview.scala

// Publish the current rules3 artifact locally, then set LORIKEET_TEST_VERSION
// to the version printed by sbt before running this end-to-end test.

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class CheckMultiFileTest extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "seconds")

  test("grades and publishes rewrites from two same-named submission files") {
    val root = Files.createTempDirectory("feedback-multifile-")
    val lab = root.resolve("lab")
    val student = root.resolve("submissions/student-0")
    val output = root.resolve("output")
    def write(path: Path, value: String): Unit =
      Files.createDirectories(path.getParent)
      Files.writeString(path, value, UTF_8)

    val ruleVersion = sys.env.getOrElse("LORIKEET_TEST_VERSION",
      fail("Set LORIKEET_TEST_VERSION to the locally published rules3 version"))
    write(lab.resolve("build.sbt"),
      s"""scalaVersion := "3.7.0"
        |semanticdbEnabled := true
        |semanticdbVersion := scalafixSemanticdb.revision
        |scalafixDependencies += "ch.epfl.systemf" % "lorikeet_3" % "$ruleVersion"
        |scalafixCaching := false
        |""".stripMargin)
    write(lab.resolve("project/plugins.sbt"),
      """addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")
        |addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
        |""".stripMargin)
    write(lab.resolve("project/build.properties"), "sbt.version=2.0.7\n")
    write(lab.resolve(".scalafmt.conf"),
      "version = 3.9.9\nrunner.dialect = scala3\nrewrite.scala3.convertToNewSyntax = true\nrewrite.scala3.removeOptionalBraces = yes\n")
    write(lab.resolve(".lorikeet.conf"),
      """max-rewrites = 10
        |rules = [
        |  { name = "First rewrite", description = "Remove the first zero", pattern = "1 + 0", rewrite = "1" }
        |  { name = "Second rewrite", description = "Remove the second zero", pattern = "2 + 0", rewrite = "2" }
        |  {
        |    name = "Discarded Boolean Result"
        |    pattern = "{ `?before`: @mult; if `?condition` then { `?effect`; true } else false; `?next`; `?after`: @mult }"
        |    rewrite = "{ `?before`: @mult; if `?condition` then { `?effect` }; `?next`; `?after`: @mult }"
        |  }
        |  {
        |    name = "Discarded False Else Branch"
        |    pattern = "{ `?before`: @mult; if `?condition` then `?body` else false; `?next`; `?after`: @mult }"
        |    rewrite = "{ `?before`: @mult; if `?condition` then `?body`; `?next`; `?after`: @mult }"
        |  }
        |  { name = "Boolean If", pattern = "if `?condition` then true else false", rewrite = "`?condition`" }
        |  { name = "Boolean If", pattern = "if `?condition` then false else (`?expression`: Boolean)", rewrite = "!`?condition` && `?expression`" }
        |  { name = "Boolean If", pattern = "if `?condition` then (`?expression`: Boolean) else true", rewrite = "!`?condition` || `?expression`" }
        |  { name = "Boolean If", pattern = "if `?condition` then true else (`?expression`: Boolean)", rewrite = "`?condition` || `?expression`" }
        |  { name = "Boolean If", pattern = "if `?condition` then (`?expression`: Boolean) else false", rewrite = "`?condition` && `?expression`" }
        |]
        |token-rules = [
        |  { name = "Boolean Negation", pattern = "!true", rewrite = "false" }
        |]
        |""".stripMargin)

    val relativeFiles = Seq(
      "src/main/scala/a/Main.scala",
      "src/main/scala/b/Main.scala"
    )
    write(student.resolve(relativeFiles(0)),
      "package a\nobject Main:\n  val value: Int = 1 + 0\n  val plainTrue = true\n  val negatedTrue = !true\n  def example(flag: Boolean): Boolean =\n    if flag then println(\"found\")\n    else false\n    true\n  def example2(flag: Boolean): Boolean =\n    if flag then\n      println(\"effect\")\n      true\n    else false\n    true\n")
    write(student.resolve(relativeFiles(1)),
      "package b\nobject Main {\n  val value: Int = 2 + 0\n  def oldStyle(flag: Boolean): Boolean = if (flag) true else false\n  def cases(c: Boolean, e: Boolean): Boolean = {\n    // Keep this note and the surrounding method intact.\n    val a = if (c) false else e\n    val b = if (c) e else true\n    val d = if (c) true else e\n    val f = if (c) e else false\n    val g = if (c || e) false else e\n    a && b && d && f && g\n  }\n}\n")
    val config = CheckTool.Config(
      labDir = lab,
      submissionsDir = student.getParent,
      diffDir = output.resolve("grading_diffs_demo"),
      historyDir = output.resolve("grading_histories_demo"),
      originalDir = output.resolve("grading_originals_demo"),
      lintDir = output.resolve("grading_reports_demo"),
      resultsFile = output.resolve("grading_results_demo.json"),
      tmpDir = output.resolve(".tmp"),
      targetFiles = relativeFiles.map(lab.resolve)
    )
    Seq(config.diffDir, config.historyDir, config.originalDir,
      config.lintDir, config.tmpDir).foreach(Files.createDirectories(_))

    val result = CheckTool.checkStudent(student, config)
    assert(result.isInstanceOf[CheckTool.IssuesFound], s"$result in $root")
    assertEquals(result.asInstanceOf[CheckTool.IssuesFound].issues,
      Map("First rewrite" -> 1, "Second rewrite" -> 1,
        "Boolean Negation" -> 1,
        "Boolean If" -> 6,
        "Discarded Boolean Result" -> 1,
        "Discarded False Else Branch" -> 1))
    CheckTool.writeResults(Seq(result), config.resultsFile)
    val bundle = ujson.read(Files.readString(
      config.historyDir.resolve("student-0.history.json"), UTF_8))
    val files = bundle("files").arr
    assertEquals(files.size, 2)
    assertEquals(files.map(_("steps").arr.size).toSet, Set(4, 7))
    assertEquals(files.map(_("steps")(0)("rule").str).toSet,
      Set("First rewrite", "Second rewrite"))
    val findHistory = files.find(_("file").str.endsWith("/a/Main.scala")).get
    val oldStyleHistory = files.find(_("file").str.endsWith("/b/Main.scala")).get
    assert(oldStyleHistory("initial").str.contains("if flag then"))
    val booleanEdits = oldStyleHistory("steps").arr.filter(_("rule").str == "Boolean If")
    assertEquals(booleanEdits.size, 6)
    assert(booleanEdits.map(_("after").str).toSet.contains("!(c || e) && (e)"))
    assert(Set("flag", "!c && e", "!c || e", "c || e", "c && e")
      .subsetOf(booleanEdits.map(_("after").str).toSet))
    assert(booleanEdits.last("code").str.contains("// Keep this note"))
    assert(booleanEdits.last("code").str.contains("object Main:"))
    assert(!booleanEdits.last("code").str.contains("if ("))
    val contextual = findHistory("steps").arr.filter(step =>
      step("rule").str.startsWith("Discarded "))
    assertEquals(contextual.size, 2)
    contextual.foreach { step =>
      assert(step("before").str.contains("else false"))
      assertEquals(step("after").str, "")
      assert(step("code").str.contains("if flag then"))
      assert(!step("code").str.contains("if (flag)"))
    }
    assert(findHistory("steps").arr.exists(_("rule").str == "Boolean Negation"))
    assert(!findHistory("lints").arr.exists(_("rule").str == "Boolean Negation"))
    def fileCount(directory: Path): Int =
      Using.resource(Files.list(directory))(_.iterator().asScala.size)
    assertEquals(fileCount(config.originalDir), 2)
    assertEquals(fileCount(config.diffDir), 2)

    val website = Paths.get("feedback/website").toAbsolutePath.normalize()
    val site = output.resolve("feedback")
    val generator = GeneratorConfig(
      data = output,
      template = website.resolve("feedback_template.html"),
      output = site,
      logs = output.resolve("logs"),
      run = Some("demo"),
      publishLab = None,
      deploymentRoot = output.resolve("deployment"),
      serve = false,
      port = 0
    )
    GenerateFeedback.generate(generator)
    val report = Using.resource(Files.list(site))(_.iterator().asScala
      .find(_.getFileName.toString.startsWith("demo-student-0-")).get)
    val html = Files.readString(report, UTF_8)
    assertEquals("class=\"timeline\"".r.findAllIn(html).length, 2)
    assert(html.contains("First rewrite"))
    assert(html.contains("Second rewrite"))

    GenerateFeedback.generate(generator.copy(publishLab = Some("multi-demo")))
    val links = Files.readAllLines(
      generator.deploymentRoot.resolve("private/links/multi-demo.csv"), UTF_8)
    assertEquals(links.size, 2)
    val token = links.get(1).split(",", 2)(1).split('/').last
    assert(Files.isRegularFile(generator.deploymentRoot
      .resolve(s"public/multi-demo/$token.html")))
  }
