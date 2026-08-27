scalaVersion := "3.7.4"

semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision
scalafixDependencies += "ch.epfl.systemf" % "lorikeet_3" % "0.1.0"
scalafixCaching := false
