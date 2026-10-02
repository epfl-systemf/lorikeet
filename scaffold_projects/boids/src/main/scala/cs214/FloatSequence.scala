package cs214

case class FloatNil() extends FloatSequence:
  override def head: Float = throw FloatSequenceException(
    "can't get head of empty sequence"
  )
  override def tail: FloatSequence = throw FloatSequenceException(
    "can't get tail of empty sequence"
  )
  override def isEmpty: Boolean = true

case class FloatCons(override val head: Float, override val tail: FloatSequence)
    extends FloatSequence:
  override def isEmpty: Boolean = false

trait FloatSequence:
  def head: Float
  def tail: FloatSequence
  def isEmpty: Boolean
  def sum: Float =
    this match
      case FloatNil()            => 0
      case FloatCons(head, tail) => head + tail.sum

class FloatSequenceException(msg: String) extends Exception(msg)
