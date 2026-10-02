package boids

import cs214.{BoidSequence, Vector2}
import java.awt.Color

object Boids:
  val DEFAULT_SIZE: Float = 1f
  val DEFAULT_COLOR: Color = Color.BLUE

// start boid
case class Boid(
    position: Vector2,
    velocity: Vector2,
    size: Float = Boids.DEFAULT_SIZE,
    color: Color = Boids.DEFAULT_COLOR
)
// end boid
