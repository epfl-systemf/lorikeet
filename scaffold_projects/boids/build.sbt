name := "boids"
scalaVersion := "3.9.0"
scalacOptions ++= Seq("-deprecation", "-feature", "-Werror")

semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision
scalafixDependencies += "ch.epfl.systemf" % "lorikeet_3" % "0.1.0"
scalafixCaching := false
