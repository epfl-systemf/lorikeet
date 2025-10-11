# Statistics

These statistics were made from the student submissions for Software Construction (CS-214).
Checks were run on the latest submission of each student.

## Find Lab, 2024 - SimplIf Rule

The `SimplIf` rule checks for unnecessary `if` statements that can be simplified, in particular:

```scala
if (cond) then x else true   => !cond || x
if (cond) then x else false  => cond && x
if (cond) then true else x   => cond || x
if (cond) then false else x  => !cond && x
```

The check was run on 421 submissions, 272 of which contained at least one instance of the pattern (over 64%) and one of which did not compile.

## Find Lab, 2024 - Vars Rule

The `Vars` rule checks for the use of `var` in the code.

The check was run on 421 submissions, 121 of which contained at least one instance of the pattern (over 28%) and one of which did not compile.

## Boids Lab, 2024 - InfixOps Rule

The `InfixOps` rule checks for infix operators used in a non-infix manner
(e.g., `a.+(b)` instead of `a + b`).

The check was run on 420 submissions, 62 of which contained at least one instance of the pattern (over 14%).

## Boids Lab, 2024 - Vars Rule

The `Vars` rule checks for the use of `var` in the code.

The check was run on 420 submissions, 84 of which contained at least one instance of the pattern (20%).
