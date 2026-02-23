package lorikeet

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3

/** Manages variable bindings during matching.
  *
  * Bindings track mappings between pattern variables (like ?x, ?T) and the
  * actual nodes they match
  */
object Bindings:
  val empty: Bindings = Bindings(Map.empty, Map.empty, Map.empty, Map.empty)

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

/** Map of pattern variables to their matched values.
  *
  * @param terms
  *   map from term variable names to matched Term trees
  * @param types
  *   map from type variable names to matched Type trees
  */
case class Bindings(
    terms: Map[String, Tree],
    types: Map[String, Type],
    multiTerms: Map[String, List[Term]],
    multiTypes: Map[String, List[Type]]
):
  import Bindings.{isEquivalent, isSeqEquivalent}

  // Private helpers

  private def checkAdd[T](
      name: String,
      value: T,
      currentMap: Map[String, T],
      equals: (T, T) => Boolean
  )(update: Map[String, T] => Bindings): Option[Bindings] =
    currentMap.get(name) match
      case Some(existing) if equals(existing, value) => Some(this)
      case Some(_)                                   => None
      case None => Some(update(currentMap + (name -> value)))

  private def getOrThrow[T](name: String, opt: Option[T], kind: String): T =
    opt.getOrElse(
      throw new Exception(s"No binding found for $kind name: $name")
    )

  // Public

  def checkAddTerm(name: String, term: Term)(using
      SemanticDocument
  ): Option[Bindings] =
    checkAdd(name, term, terms, isEquivalent)((m) => this.copy(terms = m))

  def checkAddType(name: String, tpe: Type)(using
      SemanticDocument
  ): Option[Bindings] =
    checkAdd(name, tpe, types, isEquivalent)((m) => this.copy(types = m))

  def checkAddMultiTerm(name: String, ts: List[Term])(using
      SemanticDocument
  ): Option[Bindings] =
    checkAdd(name, ts, multiTerms, isSeqEquivalent)((m) =>
      this.copy(multiTerms = m)
    )

  def checkAddMultiType(name: String, ts: List[Type])(using
      SemanticDocument
  ): Option[Bindings] =
    checkAdd(name, ts, multiTypes, isSeqEquivalent)((m) =>
      this.copy(multiTypes = m)
    )

  def getTerm(name: String): Option[Term] =
    terms.get(name).collect { case t: Term => t }
  def getType(name: String): Option[Type] = types.get(name)
  def getMultiTerm(name: String): Option[List[Term]] = multiTerms.get(name)
  def getMultiType(name: String): Option[List[Type]] = multiTypes.get(name)

  def getTermOrThrow(name: String): Term =
    getOrThrow(name, getTerm(name), "term")
  def getTypeOrThrow(name: String): Type =
    getOrThrow(name, getType(name), "type")
  def getMultiTermOrThrow(name: String): List[Term] =
    getOrThrow(name, getMultiTerm(name), "multi-term")
  def getMultiTypeOrThrow(name: String): List[Type] =
    getOrThrow(name, getMultiType(name), "multi-type")
