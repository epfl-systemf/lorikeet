#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
lab="$root/scaffold_projects/boids"
fixture="$lab/src/main/scala/boids/BoidsRuleCheck.scala"
vector="$lab/src/main/scala/cs214/Vector2.scala"
vector_hash="$(sha256sum "$vector")"
cmp "$lab/.lorikeet.conf" "$root/feedback/feedback_automation/sample-configs/boids.lorikeet.conf"
test ! -e "$fixture"
trap 'rm -f "$fixture"' EXIT

cat > "$fixture" <<'SCALA'
package boids
import cs214.{BoidCons, BoidNil, BoidSequence, Vector2}

object BoidsRuleCheck:
  def constructors(x: Float, y: Float, v: Vector2) = (new Vector2(x, y), new Vector2(v))
  def constant = new Vector2(1f, 0f)
  def empty(bs: BoidSequence) = bs.length == 0
  def keepLength(bs: BoidSequence) = bs.length == 1
  def keepList(xs: List[Int]) = xs.length == 0
  def distance(a: Vector2, b: Vector2) = (a - b).norm
  def keepNorm(a: Vector2, b: Vector2) = (a + b).norm
  def mapped(allBoids: BoidSequence, physics: Physics): BoidSequence =
    def step(seq: BoidSequence): BoidSequence = seq match
      case BoidNil() => BoidNil()
      case BoidCons(boid, tail) =>
        val updated = tickBoid(boid, allBoids, physics)
        BoidCons(updated, step(tail))
    step(allBoids)
SCALA

(cd "$lab" && LINT_LEVEL=none sbt \
  'scalafix MetaRule --files=src/main/scala/boids/BoidsRuleCheck.scala' \
  'scalafix MetaRule --files=src/main/scala/cs214/Vector2.scala')
test "$vector_hash" = "$(sha256sum "$vector")"
rg -q 'cs214.Vector2\(x, y\), cs214.Vector2\(v\)' "$fixture"
rg -q 'def constant = Vector2.UnitRight' "$fixture"
rg -q 'def empty\(bs: BoidSequence\) = bs.isEmpty' "$fixture"
rg -q 'def keepLength\(bs: BoidSequence\) = bs.length == 1' "$fixture"
rg -q 'def keepList\(xs: List\[Int\]\) = xs.length == 0' "$fixture"
rg -q 'def distance\(a: Vector2, b: Vector2\) = a.distanceTo\(b\)' "$fixture"
rg -q 'def keepNorm\(a: Vector2, b: Vector2\) = \(a \+ b\).norm' "$fixture"
rg -q 'allBoids.mapBoid\(boid => tickBoid\(boid, allBoids, physics\)\)' "$fixture"
