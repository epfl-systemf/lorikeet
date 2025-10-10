# Code Quality Feedback Tool

## Development

### Run Tests

```bash
sbt "tests / test"
```

### Publish Locally

```bash
sbt "custom-rules / publishLocal"
```

### Using a Local Rule

1. Publish the rule locally using the command above.
2. Add sbt-scalafix to your `project/plugins.sbt` file:

  ```scala
  addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.4")
  ```

3. Add the dependency and semanticdb support to your `build.sbt` file:

```scala
inThisBuild(
  List(
    scalaVersion := "3.7.2",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision
  )
)

scalafixDependencies += "ch.epfl.sidoniebouthors" % "custom-rules_3" % "0.1.0-SNAPSHOT"
```

4. Create a `.scalafix.conf` file in the root of your project with the following content (where `CustomRule` is the name of the rule to run):

```hocon
rules = [
  CustomRule
]
```

5. Run Scalafix on your project:

```bash
sbt scalafix
```

