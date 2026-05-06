package lorikeet

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
            // System.err.println(
            //   "Unsupported signature type for tree: " + t.syntax
            // )
            None
      case _ =>
        // System.err.println("No symbol info found for tree: " + t.syntax)
        None

  /** Get the literal type of a literal tree */
  def getLiteralType(t: Tree): Option[Type] =
    t match
      case Lit.Int(_)     => Some(Type.Name("Int"))
      case Lit.String(_)  => Some(Type.Name("String"))
      case Lit.Double(_)  => Some(Type.Name("Double"))
      case Lit.Float(_)   => Some(Type.Name("Float"))
      case Lit.Boolean(_) => Some(Type.Name("Boolean"))
      case Lit.Char(_)    => Some(Type.Name("Char"))
      case Lit.Long(_)    => Some(Type.Name("Long"))
      case Lit.Short(_)   => Some(Type.Name("Short"))
      case Lit.Byte(_)    => Some(Type.Name("Byte"))
      case Lit.Null()     => Some(Type.Name("Null"))
      case Lit.Unit()     => Some(Type.Name("Unit"))
      case _              => None

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
    val literalType = getLiteralType(cand)

    (candType, literalType) match
      case (Some(_), _)                                                    => ()
      case (None, Some(litType)) if patType.structure == litType.structure =>
        // TODO: figure out lit types and bindings
        return Some(bindings)
      case _ => return None

    compareSemanticTypesWithPattern(
      candType.get,
      patType,
      bindings
    )

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
        bindings.get[Type](name.stripPrefix("?")) match
          case Some(tpe) => PatternTypeResolution.Resolved(tpe)
          case None      => PatternTypeResolution.UnboundVariable(name)
      case _ =>
        PatternTypeResolution.Resolved(patType)

  /** Compare a semantic type with a syntactic pattern type.
    *
    * Currently supports only simple type names (Int, String, etc.) and generic
    * types (List[Int], etc.)
    *
    * TODO: Extend to support more complex types
    *
    * @param semType
    *   the semantic type from SemanticDB
    * @param patType
    *   the syntactic type from the pattern
    * @param bindings
    *   current bindings for type variables
    * @return
    *   Some(updatedBindings) if types match, None otherwise
    */
  private def compareSemanticTypesWithPattern(
      semType: SemanticType,
      patType: Type,
      bindings: Bindings
  ): MatchResult =
    resolvePatternType(patType, bindings) match
      case PatternTypeResolution.Wildcard              => Some(bindings)
      case PatternTypeResolution.UnboundVariable(name) =>
        // Cannot bind to semantic types without explicit ascription
        None
      case PatternTypeResolution.Resolved(resolvedType) =>
        // Compare resolved pattern type with candidate's semantic type
        (semType, resolvedType) match
          // Simple types
          case (TypeRef(_, candSymbol, Nil), Type.Name(patTypeName)) =>
            if candSymbol.displayName == patTypeName then Some(bindings)
            else None

          // Generic types
          case (
                TypeRef(_, candSymbol, typeArgs),
                Type.Apply.After_4_6_0(Type.Name(patTypeName), typeArgClause)
              ) =>
            if candSymbol.displayName == patTypeName
              && typeArgs.length == typeArgClause.values.length
              && typeArgs
                .zip(typeArgClause.values)
                .map { case (semArg, synArg) =>
                  compareSemanticTypesWithPattern(
                    semArg,
                    synArg,
                    bindings
                  )
                }
                .forall(_.isDefined)
            then Some(bindings)
            else None

          // TODO: Handle other forms of types

          case _ =>
            System.err.println(
              s"Unsupported type comparison between candidate type "
                + s" $semType and pattern type $patType"
            )
            None

  private enum PatternTypeResolution:
    // Wildcard (?)
    case Wildcard
    // Unbound type variable (?T)
    case UnboundVariable(name: String)
    // Type variable resolved to concrete type
    case Resolved(tpe: Type)
