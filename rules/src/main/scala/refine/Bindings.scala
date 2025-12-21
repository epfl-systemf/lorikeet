package refine

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3

/** Manages variable bindings during matching.
  *
  * Bindings track mappings between pattern variables (like ?x, ?T) and the
  * actual nodes they match
  */
object Bindings:
  val empty: Bindings = Bindings(Map.empty, Map.empty)
  def sameBinding(t1: Tree, t2: Tree)(using
      doc: SemanticDocument
  ): Boolean =
    (t1.symbol, t2.symbol) match
      case (Symbol.None, _) | (_, Symbol.None) => t1.structure == t2.structure
      case (s1, s2) => s1 == s2 && t1.structure == t2.structure

/** Map of pattern variables to their matched values.
  *
  * @param terms
  *   map from term variable names to matched Term trees
  * @param types
  *   map from type variable names to matched Type trees
  */
case class Bindings(
    terms: Map[String, Tree],
    types: Map[String, Type]
):
  import Bindings.sameBinding

  /** Attempt to bind a term variable to a value.
    *
    * @param name
    *   the variable name (without the ? prefix)
    * @param term
    *   the term to bind
    * @return
    *   Some(updated bindings) if binding succeeds, None if conflict
    */
  def checkAddTerm(name: String, term: Term)(using
      doc: SemanticDocument
  ): Option[Bindings] =
    terms.get(name) match
      case Some(t) if sameBinding(t, term) => Some(this)
      case Some(x)                         => None
      case None => Some(this.copy(terms = terms + (name -> term)))

  /** Attempt to bind a type variable to a value.
    *
    * @param name
    *   the variable name (without the ? prefix)
    * @param tpe
    *   the type to bind
    * @return
    *   Some(updated bindings) if binding succeeds, None if conflict
    */
  def checkAddType(name: String, tpe: Type)(using
      doc: SemanticDocument
  ): Option[Bindings] =
    types.get(name) match
      case Some(t) if sameBinding(t, tpe) => Some(this)
      case Some(x)                        => None
      case None => Some(this.copy(types = types + (name -> tpe)))

  /** Get a bound term variable.
    *
    * @param name
    *   the variable name (without the ? prefix)
    * @return
    *   the bound term if it exists and is a Term
    */
  def getTerm(name: String): Option[Term] =
    terms.get(name).collect { case t: Term => t }

  /** Get a bound type variable.
    *
    * @param name
    *   the variable name (without the ? prefix)
    * @return
    *   the bound type if it exists
    */
  def getType(name: String): Option[Type] =
    types.get(name)
