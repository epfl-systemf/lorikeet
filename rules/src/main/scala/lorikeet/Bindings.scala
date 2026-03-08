package lorikeet

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3

/** A semantic matcher to compare trees structurally while also considering
  * symbol equivalence (SemanticDB)
  */
case class SemanticMatcher()(using doc: SemanticDocument)
    extends AbstractMatcher:
  def compareTrees(t1: Tree, t2: Tree, bindings: Bindings): Option[Bindings] =
    (t1.symbol, t2.symbol) match
      case (Symbol.None, _) | (_, Symbol.None) =>
        compareProducts(t1, t2, bindings)
      case (s1, s2) =>
        if s1 == s2 then compareProducts(t1, t2, bindings) else None

  def compareTrees(a: Tree, b: Tree): Boolean =
    compareTrees(a, b, Bindings.empty).isDefined

  def compareFields(pat: Any, cand: Any): Boolean =
    compareFields(pat, cand, Bindings.empty).isDefined

sealed trait Binding
object Binding:
  case class TermValue(term: Term) extends Binding
  case class TypeValue(tpe: Type) extends Binding
  case class MultiTermValue(terms: List[Term]) extends Binding
  case class MultiNameValue(terms: List[Term.Name]) extends Binding
  case class MultiTypeValue(types: List[Type]) extends Binding

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
      case Binding.MultiNameValue(ts) => Some(ts)
      case _                          => None
  given multiNameAsBinding: AsBinding[List[Term.Name]] with
    def wrap(terms: List[Term.Name]) = Binding.MultiNameValue(terms)
    def extract(b: Binding) = b match
      case Binding.MultiNameValue(ts) => Some(ts)
      case Binding.MultiTermValue(ts) =>
        // Extract Term.Names from Terms if possible
        val names = ts.collect { case Term.Name(name) => Term.Name(name) }
        if (names.size == ts.size) then Some(names) else None
      case _ => None
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

  val semMatcher = SemanticMatcher()

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
          case Some(existingValue)
              if semMatcher.compareFields(existingValue, value) =>
            Some(this)
          // Different binding of the same type, conflict
          case Some(existingValue) =>
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
            throw new Exception(
              s"Binding type mismatch for '$name': binding is used as a different type than it was bound with."
            )
      case None => None

  def getOrThrow[T](name: String)(using extractor: AsBinding[T]): T =
    get(name) match
      case Some(value) => value
      case None =>
        throw new Exception(s"Expected binding for '$name' not found")
