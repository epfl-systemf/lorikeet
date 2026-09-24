# Lorikeet

Lorikeet is a Scalafix-based code quality feedback tool that lets you define custom rules using  query patterns and rewrite templates. It can be used for automated grading and feedback on student assignments, as well as a dev tool to simplify writing custom Scalafix rules.

Writing rules requires no knowledge of Scala's AST or the Scalafix API, and allows you to express complex patterns and rewrites with a simple and intuitive syntax.

The included [Check.scala](grading/scripts/Check.scala) script runs custom rules over many submissions and produces feedback and summary statistics.

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
├── server/                   # Webapp backend
└── webapp/                   # Webapp frontend
```

See the README in the respective subfolders for more information.

## Local usage

### Run Lorikeet on one project

1. Publish Lorikeet locally. Use the version printed by sbt in the target project.

```bash
cd scalafix
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

Use [Check.scala](grading/scripts/Check.scala) with a configured scaffold project and submissions arranged as `submissions/<student-id>/<attempt>/<target-file>.scala`.

1. Configure `LAB_DIR_NAME`, `SUBMISSIONS_DIR_NAME`, and `TARGET_FILES` at the top of `Check.scala`. The scaffold must contain `.lorikeet.conf`, `.scalafmt.conf`, and the sbt setup above.
2. Run the batch check from the repository root:

```bash
scala-cli run grading/scripts/Check.scala
```

3. Generate and serve the offline feedback site from the timestamped grading outputs:

```bash
python feedback_website/generate_feedback.py \
  --data . --include-scalafmt --serve
```

`Check.scala` records original sources, per-rewrite histories, lint reports, and final diffs. `--include-scalafmt` exposes the initial formatting pass as rewrite 0; omit it if that step is not useful.

### Review generated feedback

Open `http://127.0.0.1:8765/generated/overview.html`, select a submission, and use Next/Back to inspect each rewrite and final observation. Review a representative sample before distribution—especially broad patterns and formatting-only changes—and update `.lorikeet.conf` rather than editing generated HTML. The editor at `http://127.0.0.1:8765/` can refine feedback text and regenerate the pages.

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
