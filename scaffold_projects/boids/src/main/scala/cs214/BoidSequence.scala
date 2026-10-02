package cs214

import boids.Boid

case class BoidNil() extends BoidSequence:
  override def head: Boid = throw BoidSequenceException(
    "can't get head of empty sequence"
  )
  override def tail: BoidSequence = throw BoidSequenceException(
    "can't get tail of empty sequence"
  )
  override def isEmpty: Boolean = true

case class BoidCons(override val head: Boid, override val tail: BoidSequence)
    extends BoidSequence:
  override def isEmpty: Boolean = false

trait BoidSequence:
  /** Returns the head of the sequence of boids, i.e. the first boid.
    *
    * @throws `BoidSequenceException`
    *   is the sequence is empty
    *
    * @returns
    *   the first boid in the sequence
    */
  def head: Boid

  /** Returns the tail of the sequence of boids, i.e. the sequence without its
    * first boid.
    *
    * @throws `BoidSequenceException`
    *   is the sequence is empty
    *
    * @return
    *   the sequence of boids without the first boid
    */
  def tail: BoidSequence

  /** Checks whether the sequence of boids is empty.
    *
    * @return
    *   `true` if the sequence is empty, else `false`
    */
  def isEmpty: Boolean

  /** Builds a new sequence of boids by applying the given function `f` to each
    * boid in the sequence.
    *
    * @param f
    *   the function to apply to each boid
    *
    * @return
    *   a new sequence of boids resulting from the application of the given
    *   function `f` to each boid in the sequence
    */
  def mapBoid(f: Boid => Boid): BoidSequence =
    this match
      case BoidNil()            => BoidNil()
      case BoidCons(head, tail) => BoidCons(f(head), tail.mapBoid(f))

  /** Builds a new sequence of floats by applying the given function `f` to each
    * float in the sequence.
    *
    * @param f
    *   the function to apply to each float
    *
    * @return
    *   a new sequence of floats resulting from the application of the given
    *   function `f` to each float in the sequence
    */
  def mapFloat(f: Boid => Float): FloatSequence =
    this match
      case BoidNil()            => FloatNil()
      case BoidCons(head, tail) => FloatCons(f(head), tail.mapFloat(f))

  /** Builds a new sequence of Vector2 by applying the given function `f` to
    * each Vector2 in the sequence.
    *
    * @param f
    *   the function to apply to each Vector2
    *
    * @return
    *   a new sequence of Vector2 resulting from the application of the given
    *   function `f` to each Vector2 in the sequence
    */
  def mapVector2(f: Boid => Vector2): Vector2Sequence =
    this match
      case BoidNil()            => Vector2Nil()
      case BoidCons(head, tail) => Vector2Cons(f(head), tail.mapVector2(f))

  /** Builds a new sequence of boids by applying the given function `f` to each
    * boid in the sequence and keeping only the ones for which it returns
    * `true`.
    *
    * @param f
    *   the function to apply to each boid
    *
    * @return
    *   a new sequence of boids consisting of all boids in the sequence for
    *   which the given function `f` returns `true`
    */
  def filter(f: Boid => Boolean): BoidSequence =
    this match
      case BoidNil()            => BoidNil()
      case BoidCons(head, tail) =>
        if f(head) then BoidCons(head, tail.filter(f)) else tail.filter(f)

  /** Applies a binary function to an initial float value and all boids in this
    * sequence, going left to right.
    *
    * @param init
    *   the initial value
    * @param f
    *   the binary function, that takes a float and a boid and returns a float
    *
    * @return
    *   the float result of inserting `f` between consecutive boids in this
    *   sequence, going left to right with the initial float value `init` on the
    *   left: `f(...f(init, b), b, ..., b)` where `b, ..., b` are the boids in
    *   this sequence. Returns `init` if this sequence is empty.
    */
  def foldLeftFloat(init: Float)(f: (Float, Boid) => Float): Float =
    this match
      case BoidNil()            => init
      case BoidCons(head, tail) => tail.foldLeftFloat(f(init, head))(f)

  /** Like `foldLeftFloat` but for boids. */
  def foldLeftBoid(init: Boid)(f: (Boid, Boid) => Boid): Boid =
    this match
      case BoidNil()            => init
      case BoidCons(head, tail) => tail.foldLeftBoid(f(init, head))(f)

  /** Like `foldLeftFloat` but for two-dimensional vectors from
    * `cs214.datatypes.Vector2`.
    */
  def foldLeftVector2(init: Vector2)(f: (Vector2, Boid) => Vector2): Vector2 =
    this match
      case BoidNil()            => init
      case BoidCons(head, tail) => tail.foldLeftVector2(f(init, head))(f)

  /** Like `foldLeftBoid` but without an initial value, starting with the head
    * of the sequence of boids instead.
    *
    * @throws `BoidSequenceException`
    *   if the sequence is empty
    */
  def reduceLeft(f: (Boid, Boid) => Boid): Boid =
    this match
      case BoidNil() =>
        throw BoidSequenceException("can't reduceLeft an empty sequence")
      case BoidCons(head, BoidNil())                 => head
      case BoidCons(head, BoidCons(otherHead, tail)) =>
        BoidCons(f(head, otherHead), tail).reduceLeft(f)

  /** Like `foldLeftFloat` but right to left. */
  def foldRightFloat(init: Float)(f: (Boid, Float) => Float): Float =
    this match
      case BoidNil()            => init
      case BoidCons(head, tail) => f(head, tail.foldRightFloat(init)(f))

  /** Like `foldLeftBoid` but right to left. */
  def foldRightBoid(init: Boid)(f: (Boid, Boid) => Boid): Boid =
    this match
      case BoidNil()            => init
      case BoidCons(head, tail) => f(head, tail.foldRightBoid(init)(f))

  /** Like `foldLeftVector2` but right to left. */
  def foldRightVector2(init: Vector2)(f: (Boid, Vector2) => Vector2): Vector2 =
    this match
      case BoidNil()            => init
      case BoidCons(head, tail) => f(head, tail.foldRightVector2(init)(f))

  /** Like `reduceLeft` but right to left.
    *
    * @throws `BoidSequenceException`
    *   if the sequence is empty
    */
  def reduceRight(f: (Boid, Boid) => Boid): Boid =
    this match
      case BoidNil() =>
        throw BoidSequenceException("can't reduceRight an empty sequence")
      case BoidCons(head, BoidNil()) => head
      case BoidCons(head, tail)      => f(head, tail.reduceRight(f))

  /** Returns the length of the sequence (the number of boids in it).
    *
    * @return
    *   the number of boids in the sequence
    */
  def length: Int =
    this match
      case BoidNil()            => 0
      case BoidCons(head, tail) => 1 + tail.length

class BoidSequenceException(msg: String) extends Exception(msg)
