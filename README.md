# Lorikeet

Lorikeet is a Scalafix-based code quality feedback tool that lets you define custom rules using  query patterns and rewrite templates. It can be used for automated grading and feedback on student assignments, as well as a dev tool to simplify writing custom Scalafix rules.

Writing rules requires no knowledge of Scala's AST or the Scalafix API, and allows you to express complex patterns and rewrites with a simple and intuitive syntax.

The included script [Check.scala](scripts/Check.scala) allows easily running a set of custom rules on a large number of student submissions, and provides detailed feedback and statistics on the results.

## ️Supported Scala Versions

Lorikeet is designed to  work for Scala 3 codebases and rules, but it also supports Scala 2 on a best-effort basis.

Query patterns and rewrite templates are currently parsed as Scala 3 in priority, with Scala 2.13 as a fallback if parsing fails. The rules can be run on both Scala 3 and Scala 2 codebases, regardless of the version they are parsed with.

Note that possible differences in AST structure may cause matching issues, and query patterns or rewrite templates that use syntax that is specific to Scala 3 will not be applicable to Scala 2 codebases.

## Development

This repo is structured as follows:

```text
.
├── examples/                 # Example usage
├── extensions/               # VSCode highlighting extension for `.lorikeet.conf` files
├── grading/                  # Student grading script & rules
├── scalafix/                 # Core logic of Lorikeet
├── scripts/                  # Student grading script
├── server/                   # Webapp backend
└── webapp/                   # Webapp frontend
```

See the README in the respective subfolders for more information.

## Usage

This section describes how to use Lorikeet:

- To use it in your own project, see the [Using the MetaRule](#using-the-metarule) section below.
- To grade student submissions, see the [Running a Check on Student Submissions](#running-a-check-on-student-submissions) section below.

For more details on how to write custom rules and the syntax of query patterns and rewrite templates, see the [Guide](GUIDE.md).

### Using the MetaRule

1. Publish the rule locally using the command above, or use the published version if available.
2. Add sbt-scalafix and sbt-scalafmt to your `project/plugins.sbt` file:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.4")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
```

3. Add the rule as a dependency and semanticdb support to your `build.sbt` file:

```scala
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

scalafixDependencies += "ch.epfl.systemf" % "lorikeet_3" % "0.1.0-SNAPSHOT"
```

4. Create a `.lorikeet.conf` file in the root of your project with your custom rule configuration (see the [Guide](GUIDE.md) for syntax and examples).

5. Run Scalafix on your project, specifying the rule name:

```bash
sbt "scalafix MetaRule"
```

6. Optionally, if you need to test and modify your rules, disable scalafix caching in your `build.sbt` file:

```scala
scalafixCaching := false
```

This is because sbt task caching will avoid rerunning a task that has already been run with the same arguments and scala input files, but changes to the custom rules configuration file `.lorikeet.conf` are not considered and would not trigger a re-run of scalafix.

### Running a Check on Student Submissions

See script [Check.scala](scripts/Check.scala).

This script expects a submission directory with the following structure:

```tree
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
configured to use the custom rules, as described above.

To use the `Check.scala` script, you also need to provide a `.scalafmt.conf` file in the root of your scaffold project. Scalafmt will be run before and after applying the rewrites to ensure proper formatting. A minimal configuration could be:

```hocon
version = 3.9.9
runner.dialect = scala3
```

The script will replace the `assignment.scala` file in the scaffold project with the ones from the submission, then compile and run scalafix check on it, collecting the results in a logs file.

Note that submissions that do not compile will be reported as such but will not be checked with scalafix. This means it may be a good idea to remove `-Xfatal-warnings` or other such flags
from the scaffold project.

The script output will be individual diffs for each submissions, as well as individual feedback (which rules matches, with their descriptions and where specifically in the code), and a summary at the end with statistics on how many submissions passed each rule.

The console output looks something like this:

```text
Diffs directory: ~evaluating/grading_diffs_2026.01.01_14.26.00
Lint reports directory: ~/evaluating/grading_reports_2026.01.01_14.26.00

Starting grading process...

-> ⚠️  ISSUES:  359355 / 0 -> Var Usage (1), If Simplification (12)
-> ⚠️  ISSUES:  361678 / 0 -> If Simplification (12)
-> ⚠️  ISSUES:  356669 / 0 -> Var Usage (5)
-> ⚠️  ISSUES:  380092 / 0 -> If Simplification (5)
-> ✅ SUCCESS: 377073 / 0
-> ⚠️  ISSUES:  378842 / 2 -> Var Usage (4), If Simplification (2)
-> ⚠️  ISSUES:  372197 / 0 -> Var Usage (3), If Simplification (4)
-> ⚠️  ISSUES:  344921 / 0 -> If Simplification (5)
-> ⚠️  ISSUES:  363557 / 0 -> Var Usage (12)
.....

--- SUMMARY ---
Total submissions: 421
Submissions with missing file: 0
Submissions with compile errors: 1
Submissions failing check: 378

--- STATISTICS ---
Submissions with Matches:
  If Simplification: 267
  Var Usage: 135
Total Rule Matches:
  If Simplification: 2609
  Var Usage: 752

Grading complete.
```

See the configuration options at the top of the script.

## Running the Lorikeet Webapp

The Lorikeet webapp has a custom UI to easily run rules on GitHub repositories. It is made of a Scala backend and a Next.js frontend, which can easily be run together with the Docker Compose setup.

For now there is no premade production setup for hosting the Lorikeet webapp.

### Prerequisites

Docker and Docker Compose installed

### Quick Start

From the repo root, run:

```bash
docker compose up --build
```

This will:

1. Build the server image with sbt with the Lorikeet rule published locally in it
2. Build the webapp image
3. Start both services: server on `http://localhost:8080` and webapp on `http://localhost:3000`

The webapp is accessible at `http://localhost:3000` and will automatically proxy requests to `/api/*` to the server.

Stop the containers with:

```bash
docker compose down
```

### Hot Reload

Both containers support live reload:

- **Webapp**: Edit TypeScript/React files in `webapp/` and changes appear immediately in the browser
- **Server**: Edit Scala files in `server/` and sbt will recompile on save

### Webapp Development

Check the README in the `server` and `webapp` folders to see more about the server API endpoints or other useful information.
