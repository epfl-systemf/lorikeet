package boids

import cs214.{BoidNil, BoidCons, BoidSequence, Vector2}
import scala.util.Random
import java.awt.Color

class World(val physics: Physics):
  def tick(boids: BoidSequence): BoidSequence = tickWorld(boids, physics)

object World:
  def createRandomBoid(physics: Physics): Boid =
    val x =
      Random.between(physics.limits.xmin.toFloat, physics.limits.xmax.toFloat)
    val y =
      Random.between(physics.limits.ymin.toFloat, physics.limits.ymax.toFloat)
    val initialPosition = Vector2(x, y)

    val initialRotation = Random.between(0f, 2 * Math.PI.toFloat)
    val initialSpeed =
      Random.between(physics.minimumSpeed, physics.maximumSpeed)
    val initialVelocity = Vector2.UnitUp.rotate(initialRotation) * initialSpeed

    Boid(initialPosition, initialVelocity)

  def createRandom(numBoids: Int, physics: Physics): BoidSequence =
    (0 until numBoids).foldLeft[BoidSequence](BoidNil()) { (seq, _) =>
      BoidCons(createRandomBoid(physics), seq)
    }

case class BoundingBox(
    xmin: Int,
    xmax: Int,
    ymin: Int,
    ymax: Int
)

case class Physics(
    limits: BoundingBox,
    minimumSpeed: Float,
    maximumSpeed: Float,
    perceptionRadius: Float,
    avoidanceRadius: Float,
    avoidanceWeight: Float,
    cohesionWeight: Float,
    alignmentWeight: Float,
    containmentWeight: Float
)

object Physics:
  val defaultLimits = BoundingBox(
    xmin = -500,
    xmax = 500,
    ymin = -350,
    ymax = 350
  )
  val default = Physics(
    limits = defaultLimits,
    minimumSpeed = 1f,
    maximumSpeed = 8f,
    perceptionRadius = 80f,
    avoidanceRadius = 15f,
    avoidanceWeight = 1f,
    cohesionWeight = 0.001f,
    alignmentWeight = 0.027f,
    containmentWeight = 0.5f
  )
