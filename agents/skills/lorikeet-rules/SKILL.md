---
name: lorikeet-rules
description: Write or translate examples and natural-language requirements into Lorikeet Scala structural rewrite, lint, or token rules in .lorikeet.conf files. Use for creating, reviewing, or debugging Lorikeet rule configurations, not for general Scalafix rule implementations.
---

# Write Lorikeet rules

Use the repository's `GUIDE.md` for the public syntax and `lorikeet/rules/src/main/scala/lorikeet/core/` for actual behavior. Look at `lorikeet/.lorikeet.conf` for current examples. Other sample configs show intended rules but may contain older syntax: for example, `?{x}` in a `rewrite` is not a captured-value reference in the current `Rewriter.scala`; use `` `?x` ``.

## Translate a request into a rule

1. Establish one concrete Scala input that **must** match, the desired output or lint message, and one nearby input that **must not** match. For prose, pin down the affected Scala construct and any type, symbol, package, or brace requirement. Ask about a missing choice if different answers would change program behavior.
2. Choose `rules` for Scala syntax trees; choose `token-rules` only for punctuation or text that the tree does not retain. A token regex must match the entire range of one Scala token; a substring inside a comment or string is not a full-token match. Omit `rewrite` for a lint-only rule.
3. Start with the concrete Scala expression or statement. Keep fixed syntax literal. Replace only varying terms, names, or types with captured metavariables such as `` `?source` ``. Use the same metavariable again when those occurrences must agree. Use bare `` `?` `` for an unconstrained value that the rewrite does not need.
4. Write the smallest valid HOCON entry, then run it on the positive and negative examples. Inspect actual matches and rewritten Scala, including imports, types, side effects, name capture, and subsequent rule applications. If it cannot be run, state that the rule is unverified.

For example, if `if ready then true else fallback` should become `ready || fallback`, keep `true` literal and capture the two varying expressions:

```hocon
rules = [{
  name = "Simplify true branch"
  pattern = """if `?condition` then true else `?fallback`"""
  rewrite = """`?condition` || `?fallback`"""
}]
```

The pattern matches a Scala tree, not its formatting. A pattern and a rewrite must each parse as a single Scala statement (`Stat`); wrap multiple statements in a block. Parsing tries Scala 3 first, then Scala 2.13, but matching against either source version still depends on its tree shape.

## Syntax that changes the match

| Need | Lorikeet syntax and behavior |
| --- | --- |
| Capture a term, name, or explicit type | `` `?x` `` in `pattern`; use `` `?x` `` in `rewrite` to insert it. Every rewrite binding must exist in the pattern. |
| Ignore one term or type | `` `?` ``; it creates no binding. |
| Match any subtree and capture it | `?{body := _}`; refer to the capture as `` `?body` `` in the rewrite. |
| Match either literal form | `?{+formA | +formB}`; `+` escapes to an ordinary structural match inside a pattern block. |
| Require a use count | ``?{(body := _) including (0 `?x`)}`` for zero occurrences; `x`, `n x`, `min(n) x`, and `max(n) x` are supported. The implementation counts `Term.Name` occurrences by spelling within that subtree, so check shadowing cases. |
| Replace uses inside a captured body | `` `?body`(`?x` --> replacement) ``; substitution uses semantic symbol matching where available. |
| Match variable-length lists | `@mult` on captured parameters, arguments, or block statements, as in `lorikeet/.lorikeet.conf`; verify the particular shape because parameter capture requires explicitly typed simple parameters. |

By default, a singleton `{ expression }` matches `expression` and vice versa. Set `match-blocks = true` only when those braces must be present, such as a brace-removal rule. With `match-ascriptions = false` (default), a type written in a pattern may constrain an unannotated candidate using SemanticDB; set it to `true` when the annotation itself must appear. An inferred type cannot be newly bound to a type metavariable. With `match-fqn = true` (default), a fully qualified name in a pattern can constrain the candidate's semantic symbol, including a shorter imported spelling; use `false` when syntactic spelling matters. `only-packages = ["a.b"]` restricts matches by package prefix.

## Execution and validation

Structural rules run in config order; Lorikeet applies the first rule's first source-order rewrite, reparses, and starts again. Token rules follow structural rules. `max-rewrites` defaults to 100 per file and stops cycles, so check that a rewrite does not recreate its own or an earlier rule's pattern. Lints describe matches in the original input; they are not a check of every intermediate rewrite.

Use an isolated fixture with the target Scala version and SemanticDB enabled. In this repo, add input/output fixtures under `lorikeet/input` and `lorikeet/output` when useful, then run `cd lorikeet && sbt "tests / test"`. For an external project configured as in `README.md`, run `sbt "scalafix MetaRule"` on scratch source; set `scalafixCaching := false` while iterating on `.lorikeet.conf`. Report whether the result was only inspected, parsed, exercised on examples, or checked by the test suite.
