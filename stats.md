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
