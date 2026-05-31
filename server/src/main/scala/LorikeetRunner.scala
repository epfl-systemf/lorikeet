val SBT_SCALAFIX_VERSION = "0.14.6"
val SBT_SCALAFMT_VERSION = "2.6.1"
val SCALAFMT_VERSION = "3.11.1"
val LORIKEET_VERSION = "0.1.0-SNAPSHOT"
val LORIKEET_DEPENDENCY = s""""ch.epfl.systemf" % "lorikeet_3" % "$LORIKEET_VERSION""""

object LorikeetRunner {

  sealed trait RunResult
  case object Success extends RunResult
  case object CompileError extends RunResult

  def run(id: String, rulePath: String, projectPath: String): RunResult = {
    println(s"Running Lorikeet with ID: $id")
    println(s"Rule: $rulePath")
    println(s"Projects: $projectPath")

    // Assume projectPath is just a local path for now

    // Clone the project to a temp directory (to avoid modifying the original)
    val originalProjectDir = os.Path(projectPath)
    val tempDir = os.temp.dir(prefix = s"lorikeet-$id-")

    val projectDir = tempDir / "project"
    val preSnap = tempDir / "pre-snapshot"
    val postSnap = tempDir / "post-snapshot"
    val lintReport = tempDir / "report.txt"

    os.copy(originalProjectDir, projectDir)

    // Inject sbt plugins for scalafmt and scalafix
    val injectedPluginsFile = projectDir / "project" / "lorikeet-injected-plugins.sbt"
    os.write(
      injectedPluginsFile,
      s"""
         |addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "$SBT_SCALAFMT_VERSION")
         |addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "$SBT_SCALAFIX_VERSION")
         |""".stripMargin
    )

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

    // Copy rule files into the project root (rule string is path)
    val ruleFile = os.Path(rulePath)
    os.copy(ruleFile, projectDir / ".lorikeet.conf")

    // Compile
    if (!compile(projectDir)) then {
      return CompileError
    }

    // Format and snapshot original
    formatCode(projectDir)
    os.copy(projectDir, preSnap)

    // Linting check
    val (lintCode, lintOut) = runScalafix(projectDir)
    val rules = ScalafixOutputProcessor.processLintReport(lintOut, lintReport, projectDir)

    // Apply fixes, reformat
    formatCode(projectDir)
    os.copy(projectDir, postSnap)

    Success
  }

  def compile(projectDir: os.Path): Boolean = {
    val result = os.proc(
      "sbt",
      "set ThisBuild / semanticdbEnabled := true",
      "compile"
    ).call(cwd = projectDir, check = false)
    result.exitCode == 0
  }

  def formatCode(projectDir: os.Path): Unit = {
    os.proc(
    "sbt",
    "scalafmtAll"
  ).call(cwd = projectDir)
  }

  def runScalafix(projectDir: os.Path): (Int, String) = {
    val result = os.proc(
      "sbt",
      "set ThisBuild / semanticdbEnabled := true",
      s"""set ThisBuild / scalafixDependencies += $LORIKEET_DEPENDENCY""",
      "scalafixEnable",
      "scalafix MetaRule"
    ).call(cwd = projectDir, check = false, mergeErrIntoOut = true)
    (result.exitCode, result.out.text())
  }
}
