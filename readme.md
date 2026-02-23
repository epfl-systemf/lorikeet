# Lorikeet

## Development

### Run Tests

```bash
sbt "tests / test"
```

### Publish Locally

```bash
sbt "lorikeet / publishLocal"
```

## Usage

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

scalafixDependencies += "ch.epfl.sidoniebouthors" % "lorikeet_3" % "0.1.0-SNAPSHOT"
```

4. Create a `.lorikeet.conf` file in the root of your project with your custom rule configuration (see how to write rules below):

```hocon
rules = [
  {
    name = "RuleName"
    pattern = """
      // query pattern syntax
    """
    rewrite = """
      // rewriting syntax
    """
  }
  // additional rules...
]
```

5. Run Scalafix on your project, specifying the rule name:

```bash
sbt "scalafix MetaRule"
```

To use the `Check.scala` script, you also need to provide a `.scalafmt.conf` file in the root of your project. Scalafmt will be run before and after applying the rewrites to ensure proper formatting. A minimal configuration could be:

```hocon
version = 3.9.9
runner.dialect = scala3
```

Optionally, if you need to test and modify your rules, disable scalafix caching in your `build.sbt` file:

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
configured to use the custom rules as described above.

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

## Writing Rules

The structure of the configuration file `.lorikeet.conf` is as follows:

```hocon
rules = [
  {
    name = "RuleName"
    pattern = """
      // query pattern syntax
    """
    rewrite = """
      // rewriting syntax
    """
  }
  // additional rules...
]
```

- `name`: The name of the rule, used to identify it when running scalafix.
- `pattern`: The query pattern to match in the code. See Matcher section below for syntax.
- `rewrite` (optional): The template to use for rewriting matched code. See Rewriter section below for syntax. If omitted, Scalafix will only report matches without rewriting them.
- `description` (optional): A description of the rule, used for feedback messages.
- `match-ascriptions` (optional, default: false): If true, type ascriptions in the pattern are matched syntactically rather than semantically. See Matcher section below for details.

The tool will search for instances of code that match the `pattern`, and replace them with the `rewrite` template. Both the `pattern` and `rewrite` fields are written in Scala 3 syntax, with additional constructs for matching and rewriting.

### Matcher

The Matcher structurally compares target code and query patterns (The `pattern` field of a rule). Query patterns are matched literally, except for special syntax that allows for more flexible matching, in particular it uses two main constructs: Metavariables and Pattern Blocks.

#### Metavariables

The syntax `` `?f` `` binds a name, term, or type to an identifier (here `f`).

- First use: Acts as a wildcard binding
- Subsequent uses (with the same name): Acts as an equality check to ensure the bound entities are identical

#### Pattern Blocks

Patterns Blocks are surrounded by `?{...}`. Everything inside the braces will be matched according to pattern rules and not literally.

| Syntax                 | Name        | Description                                                     |
| ---------------------- | ----------- | --------------------------------------------------------------- |
| `_`                    | Wildcard    | Matches anything                                                |
| `pat1 \| pat2`         | Alternative | Matches if the candidate matches either `pat1` or `pat2`        |
| `+expr`                | Escape      | Matched if the candidate matches the `expr` literally           |
| `ident := pat`         | Binding     | Matches `pat` and binds the capture to `ident`                  |
| `pat including (uses)` | Including   | Matches `pat` and satisfies symbol count conditions (see below) |

#### Including patterns

The `uses` inside an `including` pattern specify constraints on the number of times certain symbols must appear in the matched tree. It is a comma-separated list of symbol count conditions of the form below:

| Syntax     | Example          | Description                                   |
| :--------- | :--------------- | :-------------------------------------------- |
| `s`        | `` `?x` ``       | Matches if symbol s is present at least once. |
| `n s`      | ``3 `?x` ``      | Matches if symbol s appears exactly n times.  |
| `min(n) s` | ``min(1) `?x` `` | Matches if symbol s appears at least n times. |
| `max(n) s` | ``max(1) `?x` `` | Matches if symbol s appears at most n times.  |

Note that `s` can be any Scala identifier, or a metavariables (like  `` `?f` ``).

#### Semantic Type Matching

By default, the tool treats optional type ascriptions in patterns as semantic constraints. This includes:

- Types of `val` and `var` declarations (`val x: Int = 1`)
- Return types in `def` declarations (`def f(): Int = 2`)
- Types in anonymous function parameter lists (`(x: Int) => 2 * x`)
- Type ascriptions (`expr: Int`)

By default, if a query pattern specifies a type (for example `val x: Int`) but the student code omits it (`val x = 1`), the Matcher unifies the pattern's type with the candidate's inferred semantic type. If a query pattern omits a type ascription, the Matcher does not impose any type constraint on the candidate.

To force strict syntactic matching instead, set `match-ascriptions = true` in the rule configuration.

### Rewriter

The Rewriter replaces matched instances with the rewrite template (the `rewrite` field of a rule).

#### Bindings

The syntax `` `?f` `` can be used to reference a binding named `f` created in the matcher, and insert the corresponding matched tree at that location.

Referencing a binding that was not created in the Matcher will result in an error.

#### Substitutions

The syntax `` `?body`(`?f` --> bar, `?g` --> foo) `` replaces occurrences of metavariable `f` and `g` within `body` with arbitrary trees `bar` and `foo` respectively.
Bindings are also substituted recursively in the substituted trees.

The Rewriter uses semantic information to ensure that only the correct symbol is substituted. For example, if `f` is bound to a method named `foo` in the Matcher, only occurrences of `foo` that refer to that specific method will be replaced, and not other methods/variables named `foo` in the same scope.

Referencing a binding that was not created in the Matcher will result in an error.

### Implementation Notes

- **Top-level matches only**: To prevent overlapping patches (which can break code), the tool only considers top-level matches. If a match is found inside another match, only the outer (parent) match is rewritten. You may need to run Scalafix multiple times to catch all nested smells.
- **Binding limits**: Currently, the tool cannot bind a semantic type to a metavariable if that type was inferred (not explicitly written in code).
- **Scalafix Bug**: the script may occasionally (~1 in 100) fail to generate a lint report due to an upstream issue, even if the rewrite is successful

## Planned

- Support for matching & binding sequences (e.g., parameter lists, argument lists, etc.)
