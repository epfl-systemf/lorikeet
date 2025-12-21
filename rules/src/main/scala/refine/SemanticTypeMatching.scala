package refine

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3

/** Handles semantic type matching using SemanticDB information.
  *
  * Used to compare types at semantic level rather than syntactic level,
  * allowing patterns to match code based on actual types rather than just type
  * annotations
  */
object SemanticTypeMatching:

  /** Get the semantic type of a tree from its symbol information
    *
    * @param tree
    *   the tree to get the type of
    * @param doc
    *   the semantic document (implicit)
    * @return
    *   Some(SemanticType) if available, None otherwise
    */
  def getSymbolSemanticType(t: Tree)(using
      doc: SemanticDocument
  ): Option[SemanticType] =
    t.symbol.info match
      case Some(info) =>
        info.signature match
          case s: ValueSignature =>
            Some(s.tpe)
          case s: MethodSignature =>
            Some(s.returnType)
          case _ =>
            System.err.println(
              "Unsupported signature type for tree: " + t.syntax
            )
            None
      case _ =>
        System.err.println("No symbol info found for tree: " + t.syntax)
        None

  /** Match a candidate tree's semantic type against a pattern type.
    *
    * Handles:
    *   - wildcard types (?)
    *   - type variables (?T) with binding resolution
    *   - simple type name matching
    *
    * @param cand
    *   the candidate tree to check
    * @param patType
    *   the pattern type to match against
    * @param bindings
    *   current bindings (for resolving type variables)
    * @return
    *   Some(bindings) if match succeeds, None otherwise
    */
  def matchTreeType(
      cand: Tree,
      patType: Type,
      bindings: Bindings
  )(using doc: SemanticDocument): MatchResult =
    val candType = getSymbolSemanticType(cand)
    if candType.isEmpty then return None

    resolvePatternType(patType, bindings) match
      case PatternTypeResolution.Wildcard              => Some(bindings)
      case PatternTypeResolution.UnboundVariable(name) =>
        // Can't bind to semantic types without explicit ascription
        // System.err.println(
        //   s"Cannot bind $name to implicit type variable: no explicit type ascription found"
        // )
        None
      case PatternTypeResolution.Resolved(resolvedType) =>
        // Compare the resolved pattern type with candidate's semantic type
        compareSemanticTypes(candType.get, resolvedType)
          .map(_ => bindings)

  /** Resolve a pattern type, handling wildcards and variable bindings
    *
    * @param patType
    *   the pattern type from the pattern
    * @param bindings
    *   current bindings to check for existing type variable bindings
    * @return
    *   what kind of pattern type this is
    */
  private def resolvePatternType(
      patType: Type,
      bindings: Bindings
  ): PatternTypeResolution =
    patType match
      case Type.Name("?") => PatternTypeResolution.Wildcard
      case Type.Name(name) if name.startsWith("?") =>
        bindings.types.get(name.stripPrefix("?")) match
          case Some(tpe) => PatternTypeResolution.Resolved(tpe)
          case None      => PatternTypeResolution.UnboundVariable(name)
      case _ =>
        PatternTypeResolution.Resolved(patType)

  /** Compare a semantic type with a syntactic pattern type.
    *
    * Currently supports only simple type names (Int, String, etc.)
    *
    * TODO: Support generic types, type applications, etc.
    *
    * @param semType
    *   the semantic type from SemanticDB
    * @param synType
    *   the syntactic type from the pattern
    * @return
    *   Some(()) if types match, None otherwise
    */
  private def compareSemanticTypes(
      semType: SemanticType,
      synType: Type
  ): Option[Unit] =
    (semType, synType) match
      case (TypeRef(_, candSymbol, Nil), Type.Name(patTypeName)) =>
        if candSymbol.displayName == patTypeName then Some(())
        else None

      // TODO: Handle generic types
      // case (TypeRef(_, candSymbol, typeArgs), Type.Apply(Type.Name(patTypeName), typeArgClause)) =>
      //   if candSymbol.displayName == patTypeName then
      //     // Compare type arguments recursively
      //     ???
      //   else None

      case _ =>
        System.err.println(
          s"Unsupported type comparison between candidate type $semType " +
            s"and pattern type $synType"
        )
        None

  private enum PatternTypeResolution:
    // Wildcard (?)
    case Wildcard
    // Unbound type variable (?T)
    case UnboundVariable(name: String)
    // Type variable resolved to concrete type
    case Resolved(tpe: Type)
