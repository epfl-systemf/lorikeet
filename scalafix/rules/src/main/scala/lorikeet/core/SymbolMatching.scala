package lorikeet.core

import scala.meta._
import scalafix.v1._

object SymbolMatching:

  object FullyQualifiedName:
    def unapply(tree: Tree): Option[String] =
      val extracted = extractPatternQualifiedName(tree)
      extracted

  def extractPatternQualifiedName(tree: Tree): Option[String] =
    def termRefSegments(ref: Term.Ref): Option[List[String]] =
      ref match
        case Term.Name(value) => Some(List(value))
        case Term.Select(qual: Term.Ref, Term.Name(value)) =>
          termRefSegments(qual).map(_ :+ value)
        case _ => None

    def typeSegments(tpe: Type): Option[List[String]] =
      tpe match
        case Type.Name(value) => Some(List(value))
        case Type.Select(qual, Type.Name(value)) =>
          termRefSegments(qual).map(_ :+ value)
        case _ => None

    val nameSegments = tree match
      case ref: Term.Ref => termRefSegments(ref)
      case tpe: Type     => typeSegments(tpe)
      case _             => None

    nameSegments.map(segments => segments.mkString(".").stripPrefix("_root_."))

  /* Match a tree node with a fully qualified name */
  def matchTreeWithFQN(cand: Tree, fqn: String)(using
      doc: SemanticDocument
  ): Boolean =
    SymbolMatcher.normalized(fqn).matches(cand)
      || extractAliasFQN(cand) == Some(fqn)

  /* Attempt to check if candidate symbol may be
   * an alias for a different symbol
   *
   * For example anything in scala/package or scala/Predef (List, String...)
   */
  def extractAliasFQN(cand: Tree)(using
      doc: SemanticDocument
  ): Option[String] =
    doc.info(cand.symbol) match
      case Some(i: SymbolInformation) =>
        i.signature match
          case MethodSignature(typeParameters, parameterLists, returnType)
              if typeParameters.isEmpty && parameterLists.isEmpty =>
            getTypeSymbol(returnType)
          case TypeSignature(typeParameters, lowerBound, upperBound)
              if lowerBound == upperBound =>
            getTypeSymbol(lowerBound)
          case _ => None
      case _ => None

  def getTypeSymbol(tpe: SemanticType)(using
      doc: SemanticDocument
  ): Option[String] =
    (tpe match
      case TypeRef(prefix, symbol, args) => Some(symbol)
      case SingleType(prefix, symbol)    => Some(symbol)
      case ThisType(symbol)              => Some(symbol)
      case SuperType(prefix, symbol)     => Some(symbol)
      case _                             => None
    ).map(x => normalizeSymbolQualifiedName(x.value))

  def normalizeSymbolQualifiedName(symbolValue: String): String =
    symbolValue
      .takeWhile(_ != '(')
      .replace('/', '.')
      .stripSuffix("#")
      .stripSuffix(".")
