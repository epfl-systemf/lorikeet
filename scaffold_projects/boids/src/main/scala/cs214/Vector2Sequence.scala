package cs214

case class Vector2Nil() extends Vector2Sequence:
  override def head: Vector2 = throw Vector2SequenceException(
    "can't get head of empty sequence"
  )
  override def tail: Vector2Sequence = throw Vector2SequenceException(
    "can't get tail of empty sequence"
  )
  override def isEmpty: Boolean = true

case class Vector2Cons(
    override val head: Vector2,
    override val tail: Vector2Sequence
) extends Vector2Sequence:
  override def isEmpty: Boolean = false

trait Vector2Sequence:
  def head: Vector2
  def tail: Vector2Sequence
  def isEmpty: Boolean
  def map(f: Vector2 => Vector2): Vector2Sequence =
    this match
      case Vector2Nil()            => Vector2Nil()
      case Vector2Cons(head, tail) => Vector2Cons(f(head), tail.map(f))
  def filter(f: Vector2 => Boolean): Vector2Sequence =
    this match
      case Vector2Nil()            => Vector2Nil()
      case Vector2Cons(head, tail) =>
        if f(head) then Vector2Cons(head, tail.filter(f)) else tail.filter(f)
  def sum: Vector2 =
    this match
      case Vector2Nil()            => Vector2.Zero
      case Vector2Cons(head, tail) => head + tail.sum

class Vector2SequenceException(msg: String) extends Exception(msg)
