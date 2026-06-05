val SBT_SCALAFIX_VERSION = "0.14.6"
val SBT_SCALAFMT_VERSION = "2.6.1"
val SCALAFMT_VERSION = "3.11.1"
val LORIKEET_VERSION = "0.1.0-SNAPSHOT"
val LORIKEET_DEPENDENCY =
  s""""ch.epfl.systemf" % "lorikeet_3" % "$LORIKEET_VERSION""""

import api._

object LorikeetRunner {

  def run(id: String, rule: String, projectLink: String): ProjectResult = {
    println(s"Running Lorikeet with ID: $id")
    println(s"Rule: $rule")
    println(s"Projects: $projectLink")

    val tempDir = os.temp.dir(prefix = s"lorikeet-$id-")

    val projectDir = tempDir / "project"
    val lintReport = tempDir / "report.txt"

    val gitResult = os
      .proc("git", "clone", projectLink, projectDir.toString)
      .call(check = false)
    if (gitResult.exitCode != 0) {
      println(s"Failed to clone project from $projectLink")
      return ProjectResult(
        path = projectLink,
        result = RunResult.FAILURE,
        diff = None,
        report = Some(gitResult.err.text()).filter(_.nonEmpty)
      )
    }
    println(s"Cloned project to ${projectDir.toString}")

    // Inject sbt plugins for scalafmt and scalafix
    val injectedPluginsFile =
      projectDir / "project" / "lorikeet-injected-plugins.sbt"
    os.write(
      injectedPluginsFile,
      s"""
         |addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "$SBT_SCALAFMT_VERSION")
         |addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "$SBT_SCALAFIX_VERSION")
         |""".stripMargin
    )
    println(s"Injected sbt plugins into ${injectedPluginsFile.toString}")

    // Inject a fallback .scalafmt.conf if the project doesn't have one
    val scalafmtConfFile = projectDir / ".scalafmt.conf"
    if (!os.exists(scalafmtConfFile)) {
      os.write(
        scalafmtConfFile,
        s"""version = "$SCALAFMT_VERSION"
           |runner.dialect = scala3
           |""".stripMargin
      )
    }
    println(s"Ensured scalafmt config at ${scalafmtConfFile.toString}")

    // Create rule files into the project root (rule is the file content)
    os.write(projectDir / ".lorikeet.conf", rule)
    println(
      s"Created lorikeet config at ${projectDir / ".lorikeet.conf".toString}"
    )

    // Compile
    if (!compile(projectDir)) then {
      val reportText = if (os.exists(lintReport)) os.read(lintReport) else ""
      return ProjectResult(
        path = projectLink,
        result = RunResult.FAILURE,
        diff = None,
        report = Some(reportText).filter(_.nonEmpty)
      )
    }
    println("Compilation successful")

    // Format and snapshot original
    formatCode(projectDir)

    os.proc("git", "add", ".").call(cwd = projectDir)
    println("Staged baseline")

    // Linting check
    val (lintCode, lintOut) = runScalafix(projectDir)
    val rules =
      ScalafixOutputProcessor.processLintReport(lintOut, lintReport, projectDir)
    println(s"Scalafix linting completed with code $lintCode")
    println(s"Identified ${rules.size} issues")
    rules.foreach { rule =>
      println(s"- ${rule.name}: ${rule.description}")
    }

    // Apply fixes, reformat
    formatCode(projectDir)

    // Create diff
    val diffResult = os
      .proc(
        "git", "diff", "-U1",
        // Exclude injected files
        "--",
        ":(exclude).scalafmt.conf",
        ":(exclude).lorikeet.conf"
      )
      .call(cwd = projectDir, check = false)
    
    val diffText = diffResult.out.text().trim

    // Read lint/report if present
    val reportText = if (os.exists(lintReport)) os.read(lintReport) else ""

    println(s"Job $id completed successfully")

    ProjectResult(
      path = projectLink,
      result = RunResult.SUCCESS,
      diff = Some(diffText).filter(_.nonEmpty),
      report = Some(reportText).filter(_.nonEmpty)
    )
  }

  def compile(projectDir: os.Path): Boolean = {
    val result = os
      .proc(
        "sbt",
        "set ThisBuild / semanticdbEnabled := true",
        "compile"
      )
      .call(cwd = projectDir, check = false)
    result.exitCode == 0
  }

  def formatCode(projectDir: os.Path): Unit = {
    os.proc(
      "sbt",
      "scalafmtAll"
    ).call(cwd = projectDir)
  }

  def runScalafix(projectDir: os.Path): (Int, String) = {
    val result = os
      .proc(
        "sbt",
        "set ThisBuild / semanticdbEnabled := true",
        s"""set ThisBuild / scalafixDependencies += $LORIKEET_DEPENDENCY""",
        "scalafixEnable",
        "scalafix MetaRule"
      )
      .call(cwd = projectDir, check = false, mergeErrIntoOut = true)
    (result.exitCode, result.out.text())
  }
}
