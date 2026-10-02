//> using file Check.scala
//> using test.dep org.scalameta::munit:1.3.6

import java.nio.file.{Files, Paths}

class DemonstrationSubmissionTest extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "seconds")

  test("find demonstration reaches later rules after earlier rewrites") {
    val root = Files.createTempDirectory("find-demonstration-")
    val scaffold = Paths.get("scaffold_projects/find").toAbsolutePath.normalize()
    val lab = root.resolve("lab")
    Seq("build.sbt", ".scalafmt.conf", ".lorikeet.conf",
      "project/plugins.sbt", "project/build.properties",
      "src/main/scala/find/cs214/Entry.scala").foreach { relative =>
      val target = lab.resolve(relative)
      Files.createDirectories(target.getParent)
      Files.copy(scaffold.resolve(relative), target)
    }
    val output = root.resolve("output")
    val cfg = CheckTool.Config(lab,
      Paths.get("student-lab-submissions/test/find"),
      output.resolve("diffs"), output.resolve("histories"),
      output.resolve("originals"), output.resolve("lints"),
      output.resolve("results.json"), output.resolve(".tmp"),
      Seq(lab.resolve("src/main/scala/find/find.scala")))
    Seq(cfg.diffDir, cfg.historyDir, cfg.originalDir, cfg.lintDir,
      cfg.tmpDir).foreach(Files.createDirectories(_))

    val result = CheckTool.checkStudent(cfg.submissionsDir.resolve("demonstration"), cfg)
    assert(result.isInstanceOf[CheckTool.IssuesFound], s"$result in $root")
    val steps = ujson.read(Files.readString(cfg.historyDir.resolve("demonstration.history.json")))("files")(0)("steps").arr
    val rules = steps.map(_("rule").str).toSeq
    assert(rules.size >= 6, s"$rules in $root")
    val combine = rules.indexOf("Combine equal Boolean branches")
    val extract = steps.indexWhere(step =>
      step("rule").str == "Extract effect from Boolean if" &&
        step("before").str.contains("0 == entry.size()")
    )
    assert(combine >= 0 && extract > combine, s"$rules in $root")
    assert(steps(combine)("code").str.contains("if entry.isDirectory() && !entry.hasChildren() ||"))
    assert(steps(extract)("code").str.contains("if foundHere then println(entry.path())"))
    assert(rules.contains("Search with early returns"))
    assert(rules.contains("if c then true else false"))
    assert(rules.contains("Line-ending semicolon"))
    assert(rules.contains("0 == Entry.size()"), s"$rules in $root")
    assert(steps.last("code").str.contains("entry.size() == 0"))
  }
