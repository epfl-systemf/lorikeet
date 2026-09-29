# Lorikeet

Lorikeet is a Scalafix-based code quality feedback tool that lets you define custom rules using  query patterns and rewrite templates. It can be used for automated grading and feedback on student assignments, as well as a dev tool to simplify writing custom Scalafix rules.

Writing rules requires no knowledge of Scala's AST or the Scalafix API, and allows you to express complex patterns and rewrites with a simple and intuitive syntax.

The included [Check.scala](feedback/feedback_automation/scripts/Check.scala) script runs custom rules over many submissions and produces feedback and summary statistics.

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
├── feedback/
│   ├── feedback_automation/ # Student grading scripts and outputs
│   ├── server/              # Student-facing deployment server
│   └── website/             # Feedback page generator and review site
├── scaffold_projects/        # Minimal projects used to compile submissions
├── lorikeet/                 # Scalafix rule implementation
└── simulationUI/
    ├── compose.yaml         # Runs both UI services
    ├── server/              # Scala backend
    └── webapp/              # Next.js frontend
```

See the README in the respective subfolders for more information.

## Local usage

### Run Lorikeet on one project

1. Publish Lorikeet locally. Use the version printed by sbt in the target project.

```bash
cd lorikeet
sbt "rules3/publishLocal"
```

2. Add Scalafix and Scalafmt to `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.4")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
```

3. Enable SemanticDB and add Lorikeet to `build.sbt`:

```scala
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

scalafixDependencies += "ch.epfl.systemf" % "lorikeet_3" % "<version>"
```

4. Add `.lorikeet.conf` ([rule syntax](GUIDE.md)), then run:

```bash
sbt "scalafix MetaRule"
```

During rule development, set `scalafixCaching := false` so `.lorikeet.conf` edits are not hidden by sbt caching.

### Generate feedback for many submissions

Use [Check.scala](feedback/feedback_automation/scripts/Check.scala) to place each submission in a clean sbt project, compile it, run Lorikeet, and collect its feedback.

1. Create a minimal scaffold (the repository includes `scaffold_projects/find`):

```tree
scaffold_projects/find/
├── .lorikeet.conf
├── .scalafmt.conf
├── build.sbt
└── project/
    ├── build.properties
    └── plugins.sbt
```

Use the sbt/Scalafix setup above, point `build.sbt` at the locally published Lorikeet version, and put assignment-specific rules in `.lorikeet.conf`. The batch script creates the target source directories and removes each copied submission after checking it.

The included `find` scaffold pins Lorikeet `0.1.0`. Before grading with it, publish the current local rules under that version from the repository root:

```bash
cd lorikeet
sbt 'set ThisBuild / version := "0.1.0"' 'rules3/publishLocal'
cd ..
```

2. Put each submission directly under its student directory. Each submitted filename must match the basename of its configured target:

```tree
student-lab-submissions/2026/find/
├── alice/
│   └── find.scala
└── bob/
    └── find.scala
```

3. Set `SCAFFOLD_DIR`, `SUBMISSIONS_DIR`, and `TARGET_FILES` at the top of `Check.scala`. Paths are relative to the repository root; `TARGET_FILES` are paths inside the scaffold. For multiple target files with the same basename, each student directory must mirror their scaffold-relative paths (for example, `src/main/scala/a/Main.scala` and `src/main/scala/b/Main.scala`). Unique basenames can still be submitted flat.

4. Run the batch check from the repository root:

```bash
scala-cli run feedback/feedback_automation/scripts/Check.scala
```

Submissions that do not compile are reported and skipped by Lorikeet.

5. Generate and serve the offline feedback site from the timestamped grading outputs:

```bash
scala-cli run feedback/website/GenerateFeedback.scala -- \
  --serve
```

`Check.scala` writes results, original sources, per-rewrite histories, lint reports, and final diffs under `feedback/feedback_automation/output/`. The feedback generator reads that directory by default. The history starts with the formatted source Lorikeet sees; each following snapshot applies one rule, then final lints are collected.

For student-facing hosting with opaque report URLs and SQLite logging, follow the [feedback server deployment guide](feedback/server/README.md).

### Review generated feedback

Open `http://127.0.0.1:8765/generated/overview.html`, select a submission, and use Next/Back to inspect each rewrite and final observation. Review a representative sample before distribution—especially broad patterns and formatting-only changes. Student-facing descriptions come from the `description` fields in the `.lorikeet.conf` used for grading; change those fields and rerun grading to update the feedback.

## Running the Lorikeet Webapp

The Lorikeet webapp has a custom UI to easily run rules on GitHub repositories. It is made of a Scala backend and a Next.js frontend, which can easily be run together with the Docker Compose setup.

For now there is no premade production setup for hosting the Lorikeet webapp.

### Prerequisites

Docker and Docker Compose installed

### Quick Start

From the repo root, run:

```bash
docker compose -f simulationUI/compose.yaml up --build
```

This will:

1. Build the server image with sbt with the Lorikeet rule published locally in it
2. Build the webapp image
3. Start both services: server on `http://localhost:8080` and webapp on `http://localhost:3000`

The webapp is accessible at `http://localhost:3000` and will automatically proxy requests to `/api/*` to the server.

Stop the containers with:

```bash
docker compose -f simulationUI/compose.yaml down
```

### Hot Reload

Both containers support live reload:

- **Webapp**: Edit TypeScript/React files in `simulationUI/webapp/` and changes appear immediately in the browser
- **Server**: Edit Scala files in `simulationUI/server/` and sbt will recompile on save

### Webapp Development

Check the README in the `simulationUI/server` and `simulationUI/webapp` folders to see more about the server API endpoints or other useful information.
