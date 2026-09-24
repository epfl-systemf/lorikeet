name := "find"
scalaVersion := "3.9.0"
scalacOptions ++= Seq("-deprecation", "-feature", "-Werror")
libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.5"
libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test

semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision
scalafixDependencies += "ch.epfl.systemf" % "lorikeet_3" % "0.1.0"
scalafixCaching := false
