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

## Usage

### Using a Local Rule

1. Publish the rule locally using the command above.
2. Add sbt-scalafix to your `project/plugins.sbt` file:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.4")
```

3. Add the dependency and semanticdb support to your `build.sbt` file:

```scala
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

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

### Running a Check on Student Submissions

See script [check.sh](scripts/check.sh).

This script expects a submission directory with the following structure:

```
submission/
├── SCIPER
│   └── 0
│       └── assignment.scala
|   └── 1
│       └── assignment.scala
|   └── ...
├── SCIPER
│   └── 0
│       └── assignment.scala
|   └── ...
└── ...
```

The script also expects a `scaffold` directory containing the lab sbt project that has been
configured to use the custom rules as described above.

The script will replace the `assignment.scala` file in the scaffold project with the ones from the submission, then compile and run scalafix check on it, collecting the results in a logs file.

Note that this script does not rewrite the submission files, it only checks them.
Additionaly, submissions that do not compile will be reported as such but will not be checked
with scalafix. This means it may be a good idea to remove `-Xfatal-warnings` or other such flags
from the scaffold project.

See the configuration options at the top of the script.

## Specs for Parsed Rules

Will be updated as I add more features.

The rules are simply written in Scala 3 with some additional syntax for pattern matching.
Patterns are surrounded by `?{...}`. Everything inside the braces will be matched according to pattern rules and not literally.

| Syntax   | Name        | Description                                           |
| -------- | ----------- | ----------------------------------------------------- |
| `_`      | Wildcard    | matches anything                                      |
| `a \| b` | Alternative | matches either pattern a or pattern b                 |
| `+a`     | Escape      | `a` will be matched literaly rather than as a pattern |
| `a << b` | Binding     | matches pattern `b` and binds to `a`                  |
