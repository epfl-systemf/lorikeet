lazy val V = _root_.scalafix.sbt.BuildInfo

// lazy val rulesCrossVersions = Seq(V.scala213, V.scala212)
lazy val scala3Version = "3.7.0"
val upickleVersion = "4.4.0"

inThisBuild(
  List(
    organization := "ch.epfl.systemf",
    homepage := Some(
      url(
        "https://github.com/systemf/lorikeet"
      )
    ),
    developers := List(
      Developer(
        "SidonieBouthors",
        "Sidonie Bouthors",
        "sidonie.bouthors@epfl.ch",
        url("https://github.com/SidonieBouthors")
      )
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  )
)

lazy val `lorikeet` = (project in file("."))
  .aggregate(
    rules.projectRefs ++
      input.projectRefs ++
      output.projectRefs ++
      tests.projectRefs: _*
  )
  .settings(
    publish / skip := true
  )

lazy val rules = projectMatrix
  .settings(
    moduleName := "lorikeet",
    libraryDependencies += ("ch.epfl.scala" %% "scalafix-core" % "0.14.3")
      .cross(CrossVersion.for3Use2_13),
    libraryDependencies ++= Seq(
      "com.github.pureconfig" %% "pureconfig-core" % "0.17.9"
    )
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatform(scalaVersions = Seq(scala3Version))

lazy val input = projectMatrix
  .settings(
    publish / skip := true
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatform(scalaVersions = Seq(scala3Version))

lazy val output = projectMatrix
  .settings(
    publish / skip := true
  )
  .defaultAxes(VirtualAxis.jvm)
  .jvmPlatform(scalaVersions = Seq(scala3Version))

lazy val testsAggregate = Project("tests", file("target/testsAggregate"))
  .aggregate(tests.projectRefs: _*)
  .settings(
    publish / skip := true
  )

lazy val tests = projectMatrix
  .settings(
    publish / skip := true,
    // The 3 lines below are needed to ensure a "medium" lint level during tests
    // Default is "full", as this is what is required by the script, but this 
    // generates lints for each rewrite
    (Test / baseDirectory) := (ThisBuild / baseDirectory).value,
    Test / fork := true,
    Test / envVars += ("LINT_LEVEL" -> "medium"),
    scalafixTestkitOutputSourceDirectories :=
      TargetAxis
        .resolve(output, Compile / unmanagedSourceDirectories)
        .value,
    scalafixTestkitInputSourceDirectories :=
      TargetAxis
        .resolve(input, Compile / unmanagedSourceDirectories)
        .value,
    scalafixTestkitInputClasspath :=
      TargetAxis.resolve(input, Compile / fullClasspath).value,
    scalafixTestkitInputScalacOptions :=
      TargetAxis.resolve(input, Compile / scalacOptions).value,
    scalafixTestkitInputScalaVersion :=
      TargetAxis.resolve(input, Compile / scalaVersion).value
  )
  .defaultAxes(
    Seq(scala3Version).map(VirtualAxis.scalaABIVersion) :+ VirtualAxis.jvm: _*
  )
  .jvmPlatform(
    scalaVersions = Seq(scala3Version),
    axisValues = Seq(TargetAxis(scala3Version)),
    settings = Seq()
  )
  .dependsOn(rules)
  .enablePlugins(ScalafixTestkitPlugin)
