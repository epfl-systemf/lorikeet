package cs214

import scala.math.*

case class Vector2(val x: Float, val y: Float):
  def this() = this(0, 0)
  def this(that: Vector2) = this(that.x, that.y)
  def this(x: Double, y: Double) = this(x.toFloat, y.toFloat)
  def +(that: Vector2) = new Vector2(this.x + that.x, this.y + that.y)
  def unary_- = new Vector2(-this.x, -this.y)
  def -(that: Vector2) = new Vector2(this.x - that.x, this.y - that.y)
  def *(scale: Float) = new Vector2(this.x * scale, this.y * scale)

  /** Dot product between this vector and `that` */
  def *(that: Vector2) = this.x * that.x + this.y * that.y
  def /(scale: Float) = this * (1 / scale)

  val norm2 = this.x * this.x + this.y * this.y
  val norm = sqrt(norm2).toFloat
  def normalized = if norm == 0 then Vector2.Zero else this / norm
  def distanceTo(that: Vector2): Float = (this - that).norm

  /** Returns the anticlockwise angle between this vector and `that`, in
    * radians.
    */
  def angleTo(that: Vector2): Float =
    val rawAngle = acos(this.normalized * that.normalized).toFloat
    if that.x > this.x then (-rawAngle + 2 * Pi).toFloat else rawAngle

  /** Returns a `Vector2` equals to this vector rotated anticlockwise by
    * `radians`.
    */
  def rotate(radians: Float) = Vector2(
    cos(radians) * x - sin(radians) * y,
    sin(radians) * x + cos(radians) * y
  )

  override def toString(): String = s"($x, $y)"

object Vector2:
  val Zero: Vector2 = new Vector2(0, 0)
  val UnitRight: Vector2 = new Vector2(1, 0)
  val UnitDown: Vector2 = new Vector2(0, 1)
  val UnitUp: Vector2 = new Vector2(0, -1)
  val UnitLeft: Vector2 = new Vector2(-1, 0)

  def apply(x: Float, y: Float): Vector2 = new Vector2(x, y)
  def apply(x: Double, y: Double): Vector2 = new Vector2(x, y)
  def apply(that: Vector2): Vector2 = new Vector2(that)
  def apply(): Vector2 = new Vector2
