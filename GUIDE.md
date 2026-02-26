# Writing Rules

This guide explains how to write custom Lorikeet rules. For setup and usage instructions, see the [README](README.md).

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

## Matcher

The Matcher structurally compares target code and query patterns (The `pattern` field of a rule). Query patterns are matched literally, except for special syntax that allows for more flexible matching, in particular it uses two main constructs: Metavariables and Pattern Blocks.

### Metavariables

The syntax `` `?f` `` binds a name, term, or type to an identifier (here `f`).

- First use: Acts as a wildcard binding
- Subsequent uses (with the same name): Acts as an equality check to ensure the bound entities are identical

### Pattern Blocks

Patterns Blocks are surrounded by `?{...}`. Everything inside the braces will be matched according to pattern rules and not literally.

| Syntax                 | Name        | Description                                                     |
| ---------------------- | ----------- | --------------------------------------------------------------- |
| `_`                    | Wildcard    | Matches anything                                                |
| `pat1 \| pat2`         | Alternative | Matches if the candidate matches either `pat1` or `pat2`        |
| `+expr`                | Escape      | Matched if the candidate matches the `expr` literally           |
| `ident := pat`         | Binding     | Matches `pat` and binds the capture to `ident`                  |
| `pat including (uses)` | Including   | Matches `pat` and satisfies symbol count conditions (see below) |

### Including patterns

The `uses` inside an `including` pattern specify constraints on the number of times certain symbols must appear in the matched tree. It is a comma-separated list of symbol count conditions of the form below:

| Syntax     | Example          | Description                                   |
| :--------- | :--------------- | :-------------------------------------------- |
| `s`        | `` `?x` ``       | Matches if symbol s is present at least once. |
| `n s`      | ``3 `?x` ``      | Matches if symbol s appears exactly n times.  |
| `min(n) s` | ``min(1) `?x` `` | Matches if symbol s appears at least n times. |
| `max(n) s` | ``max(1) `?x` `` | Matches if symbol s appears at most n times.  |

Note that `s` can be any Scala identifier, or a metavariables (like  `` `?f` ``).

### Semantic Type Matching

By default, the tool treats optional type ascriptions in patterns as semantic constraints. This includes:

- Types of `val` and `var` declarations (`val x: Int = 1`)
- Return types in `def` declarations (`def f(): Int = 2`)
- Types in anonymous function parameter lists (`(x: Int) => 2 * x`)
- Type ascriptions (`expr: Int`)

By default, if a query pattern specifies a type (for example `val x: Int`) but the student code omits it (`val x = 1`), the Matcher unifies the pattern's type with the candidate's inferred semantic type. If a query pattern omits a type ascription, the Matcher does not impose any type constraint on the candidate.

To force strict syntactic matching instead, set `match-ascriptions = true` in the rule configuration.

## Rewriter

The Rewriter replaces matched instances with the rewrite template (the `rewrite` field of a rule).

### Bindings

The syntax `` `?f` `` can be used to reference a binding named `f` created in the matcher, and insert the corresponding matched tree at that location.

Referencing a binding that was not created in the Matcher will result in an error.

### Substitutions

The syntax `` `?body`(`?f` --> bar, `?g` --> foo) `` replaces occurrences of metavariable `f` and `g` within `body` with arbitrary trees `bar` and `foo` respectively.
Bindings are also substituted recursively in the substituted trees.

The Rewriter uses semantic information to ensure that only the correct symbol is substituted. For example, if `f` is bound to a method named `foo` in the Matcher, only occurrences of `foo` that refer to that specific method will be replaced, and not other methods/variables named `foo` in the same scope.

Referencing a binding that was not created in the Matcher will result in an error.

## Implementation Notes

- **Top-level matches only**: To prevent overlapping patches (which can break code), the tool only considers top-level matches. If a match is found inside another match, only the outer (parent) match is rewritten. You may need to run Scalafix multiple times to catch all nested smells.
- **Binding limits**: Currently, the tool cannot bind a semantic type to a metavariable if that type was inferred (not explicitly written in code).
- **Scalafix Bug**: the script may occasionally (~1 in 100) fail to generate a lint report due to an upstream issue, even if the rewrite is successful
