val scala3Version = "3.8.3"
val tapirVersion = "1.13.19"

lazy val root = project
  .in(file("."))
  .settings(
    name := "lorikeet-server",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.3.0" % Test,

    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
      "org.http4s" %% "http4s-ember-server" % "0.23.32",
      "io.circe" %% "circe-generic" % "0.14.15",
      "ch.qos.logback" % "logback-classic" % "1.5.32",
      "com.lihaoyi" %% "os-lib" % "0.11.3"
    )
  )
