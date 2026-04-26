package lorikeet

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

  def getTreeSymbolName(cand: Tree)(using
      doc: SemanticDocument
  ) =
    doc.info(cand.symbol) match
      case Some(i: SymbolInformation) =>
        i.signature match
          case MethodSignature(typeParameters, parameterLists, returnType) =>
            getTypeSymbol(returnType)
          case TypeSignature(typeParameters, lowerBound, upperBound) =>
            getTypeSymbol(lowerBound)
          case _ =>
            cand.symbol.value match
              case s if s.contains("package") => None
              case s => Some(normalizeSymbolQualifiedName(s))
      case None => None

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
