# Boids

This is a logic-only Scala scaffold for implementing a flock of *boids*. A
boid follows four weighted rules in a rectangular world:

1. **Avoidance:** move away from every other boid within the avoidance radius,
   with force magnitude proportional to the inverse of distance. Two boids at
   the same position exert zero avoidance force.
2. **Cohesion:** move toward the average position of every other boid within
   the perception radius. The force is zero when there are no such boids.
3. **Alignment:** move toward the average velocity of every other boid within
   the perception radius. The force is zero when there are no such boids.
4. **Containment:** apply a unit force in the opposite cardinal direction for
   every world boundary that the boid has crossed.

All boids have unit mass, so the weighted sum of these forces is the
acceleration. The world advances in discrete ticks:

```text
position(next) = position + velocity
velocity(next) = clamp(velocity + acceleration, minimumSpeed, maximumSpeed)
```

## Files and API

Write all assignment logic in `src/main/scala/boids/BoidLogic.scala`. The
support files define the API and should not need changes:

- `Boid` has behavioral fields `position: Vector2` and `velocity: Vector2`,
  plus cosmetic `size` and `color` fields.
- `BoundingBox` stores `xmin`, `xmax`, `ymin`, and `ymax`.
- `Physics` stores the world limits, speed bounds, perception and avoidance
  radii, and the four rule weights.
- `BoidSequence`, `Vector2Sequence`, and `FloatSequence` provide immutable
  `Cons`/`Nil` collections and operations such as `mapBoid`, `mapVector2`,
  `filter`, `fold`, `sum`, `length`, and `isEmpty`.
- `Vector2` provides vector arithmetic, `norm`, `normalized`, `distanceTo`, and
  the constants `Zero`, `UnitRight`, `UnitDown`, `UnitUp`, and `UnitLeft`.

The required functions are:

```scala
def boidsWithinRadius(
    thisBoid: Boid,
    boids: BoidSequence,
    radius: Float
): BoidSequence

def avoidanceForce(
    thisBoid: Boid,
    boidsWithinAvoidanceRadius: BoidSequence
): Vector2

def cohesionForce(
    thisBoid: Boid,
    boidsWithinPerceptionRadius: BoidSequence
): Vector2

def alignmentForce(
    thisBoid: Boid,
    boidsWithinPerceptionRadius: BoidSequence
): Vector2

def containmentForce(thisBoid: Boid, limits: BoundingBox): Vector2

def totalForce(thisBoid: Boid, allBoids: BoidSequence, physics: Physics): Vector2

def clampVelocity(
    velocity: Vector2,
    minimumSpeed: Float,
    maximumSpeed: Float
): Vector2

def tickBoid(thisBoid: Boid, allBoids: BoidSequence, physics: Physics): Boid

def tickWorld(allBoids: BoidSequence, physics: Physics): BoidSequence
```

`boidsWithinRadius` must exclude `thisBoid` and retain only boids whose
distance is strictly less than `radius`. `totalForce` must calculate the
perception and avoidance neighborhoods, then return the weighted sum in the
order-independent `Vector2` domain. `tickBoid` must use the original flock
when computing acceleration, and `tickWorld` must update every boid from that
same original flock.

## Build and Lorikeet

The project is configured for Scala 3, SemanticDB, Scalafmt, and Lorikeet
`ch.epfl.systemf:lorikeet_3:0.1.0`, matching the Find scaffold. Its
`.lorikeet.conf` includes `../common.lorikeet.conf` and the Boids rules: it
contains structural rewrites for common infix/conditional forms, immutable
style and loop shapes, predefined `Vector2` constants, zero-vector arithmetic,
and direct `BoidCons`/`BoidNil` construction. These are syntax rules only; they
do not claim to prove simulation behavior.

To test local Lorikeet changes, publish it from the repository root and set
`scalafixDependencies` in `build.sbt` to the version printed by sbt:

```bash
cd lorikeet
sbt "rules3/publishLocal"
```

After adding `src/main/scala/boids/BoidLogic.scala`, format, compile, and run
the configured rules on that file:

```bash
cd scaffold_projects/boids
sbt scalafmt
sbt compile
sbt "scalafix MetaRule --files=src/main/scala/boids/BoidLogic.scala"
```

Lorikeet caching is disabled in `build.sbt`, so edits to `.lorikeet.conf` are
observed on the next invocation. The normal Scalafix behavior applies rewrite
rules to the selected source file; inspect the resulting diff before keeping
those changes.
