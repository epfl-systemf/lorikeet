package lorikeet

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3

sealed trait Binding:
  import Binding._
  def isEquivalentTo(other: Binding)(using SemanticDocument): Boolean =
    (this, other) match
      case (TermValue(t1), TermValue(t2)) => isEquivalent(t1, t2)
      case (TypeValue(t1), TypeValue(t2)) => isEquivalent(t1, t2)
      case (MultiTermValue(ts1), MultiTermValue(ts2)) =>
        isSeqEquivalent(ts1, ts2)
      case (MultiTypeValue(ts1), MultiTypeValue(ts2)) =>
        isSeqEquivalent(ts1, ts2)
      case _ => false
object Binding:
  case class TermValue(term: Term) extends Binding
  case class TypeValue(tpe: Type) extends Binding
  case class MultiTermValue(terms: List[Term]) extends Binding
  case class MultiTypeValue(types: List[Type]) extends Binding

  // Compares two trees for equivalence
  def isEquivalent(t1: Tree, t2: Tree)(using
      doc: SemanticDocument
  ): Boolean =
    (t1.symbol, t2.symbol) match
      case (Symbol.None, _) | (_, Symbol.None) => t1.structure == t2.structure
      case (s1, s2) => s1 == s2 && t1.structure == t2.structure

  // Compares two sequences of trees element-wise
  def isSeqEquivalent(l1: List[Tree], l2: List[Tree])(using
      doc: SemanticDocument
  ): Boolean =
    l1.size == l2.size && l1.zip(l2).forall(isEquivalent)

trait AsBinding[T]:
  def wrap(value: T): Binding
  def extract(b: Binding): Option[T]

object AsBinding:
  given termAsBinding: AsBinding[Term] with
    def wrap(term: Term) = Binding.TermValue(term)
    def extract(b: Binding) = b match
      case Binding.TermValue(t) => Some(t)
      case _                    => None
  given typeAsBinding: AsBinding[Type] with
    def wrap(tpe: Type) = Binding.TypeValue(tpe)
    def extract(b: Binding) = b match
      case Binding.TypeValue(t) => Some(t)
      case _                    => None
  given multiTermAsBinding: AsBinding[List[Term]] with
    def wrap(terms: List[Term]) = Binding.MultiTermValue(terms)
    def extract(b: Binding) = b match
      case Binding.MultiTermValue(ts) => Some(ts)
      case _                          => None
  given multiTypeAsBinding: AsBinding[List[Type]] with
    def wrap(types: List[Type]) = Binding.MultiTypeValue(types)
    def extract(b: Binding) = b match
      case Binding.MultiTypeValue(ts) => Some(ts)
      case _                          => None

/** Manages variable bindings during matching.
  *
  * Bindings track mappings between pattern variables (like ?x, ?T) and the
  * actual nodes they match
  */
object Bindings:
  def empty(using doc: SemanticDocument): Bindings = Bindings(Map.empty)

/** Map of pattern variables to their matched values.
  *
  * @param terms
  *   map from term variable names to matched Term trees
  * @param types
  *   map from type variable names to matched Type trees
  */
case class Bindings(
    bindings: Map[String, Binding]
)(using doc: SemanticDocument):
  def add[T](
      name: String,
      value: T
  )(using format: AsBinding[T]): Option[Bindings] =
    val newBinding = format.wrap(value)
    bindings.get(name) match
      case None =>
        Some(this.copy(bindings = bindings + (name -> newBinding)))
      case Some(existing) =>
        format.extract(existing) match
          // Equivalent binding, no conflict
          case Some(existingValue) if existing.isEquivalentTo(newBinding) =>
            Some(this)
          // Different binding of the same type, conflict
          case Some(_) =>
            None
          // Different type, error
          case None =>
            throw new Exception(
              s"Binding type mismatch for variable '$name': existing binding is of a different type."
            )

  def get[T](name: String)(using extractor: AsBinding[T]): Option[T] =
    bindings.get(name) match
      case Some(b) =>
        extractor.extract(b) match
          case Some(value) => Some(value)
          case None =>
            throw new Exception(s"Binding type mismatch for '$name'")
      case None => None

  def getOrThrow[T](name: String)(using extractor: AsBinding[T]): T =
    get(name) match
      case Some(value) => value
      case None =>
        throw new Exception(s"Expected binding for '$name' not found")
