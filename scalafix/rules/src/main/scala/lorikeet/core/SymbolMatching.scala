package lorikeet.core

import scala.meta._
import scalafix.v1._
import scala.collection.immutable.HashMap

object SymbolMatching:

  object FullyQualifiedName:
    def unapply(tree: Tree): Option[String] =
      val extracted = extractPatternQualifiedName(tree)
      // FQN cannot include metavariables
      extracted
        // FQN cannot include metavariables
        .filter(segs => segs.forall(seg => !seg.startsWith("?")))
        .map(_.mkString("."))

  /** With a SemanticDocument, will do stricter checks to see if truly this is a
    * fully qualified name.
    */
  def extractPatternQualifiedName(tree: Tree)(using
      doc: Option[SemanticDocument] = None
  ): Option[List[String]] =
    def isTypeOrPackage(name: Term.Name): Boolean =
      doc match
        case Some(d) =>
          given SemanticDocument = d
          name.symbol.info match
            case Some(info) =>
              info.isPackage || info.isObject || info.isClass ||
              info.isTrait || info.isType || info.isInterface
            case None => name.value.head.isUpper
        case None => true

    def termRefSegments(ref: Term.Ref): Option[List[String]] =
      ref match
        case name @ Term.Name(value)
            if isIdentifier(value) && isTypeOrPackage(name) =>
          Some(List(value))
        case Term.Select(qual: Term.Ref, Term.Name(value)) =>
          termRefSegments(qual).map(_ :+ value)
        case _ => None

    def typeSegments(tpe: Type): Option[List[String]] =
      tpe match
        case Type.Name(value) if isIdentifier(value) => Some(List(value))
        case Type.Select(qual, Type.Name(value)) =>
          termRefSegments(qual).map(_ :+ value)
        case Type.Singleton(ref: Term.Ref) =>
          termRefSegments(ref)
        case _ => None

    tree match
      case ref: Term.Ref => termRefSegments(ref)
      case tpe: Type     => typeSegments(tpe)
      case _             => None

  def isIdentifier(name: String): Boolean =
    name.nonEmpty &&
      (name == "???" || name.head.isUnicodeIdentifierStart &&
        name.forall(c => c.isUnicodeIdentifierPart || c == '_'))

  /* Match a tree node with a fully qualified name */
  def matchTreeWithFQN(
      cand: Tree,
      fqn: String,
      defaults: Map[String, List[Symbol]]
  )(using doc: SemanticDocument): Boolean =
    // Check if the candidate symbol is in the default imports
    defaults
      .getOrElse(fqn, Nil)
      .exists(fqnSym => SymbolMatcher.exact(fqnSym.toString).matches(cand))
    // Check if candidate matches the FQN directly
      || SymbolMatcher.normalized(fqn).matches(cand)
      // Check if candidate is an alias for the FQN
      || extractAliasFQN(cand) == Some(fqn)

  /* Attempt to check if candidate symbol may be
   * an alias for a different symbol
   *
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

  private def membersOf(
      containerSymbol: String
  )(using symtab: Symtab): List[Symbol] = {

    val sym = Symbol(containerSymbol)

    val directDeclarations: List[SymbolInformation] =
      sym.info.toList.flatMap { info =>
        info.signature match {
          case ClassSignature(_, _, _, decls) => decls
          case _                              => Nil
        }
      }

    directDeclarations.flatMap { decl =>
      if decl.isConstructor
        || decl.isPrivate
        || decl.isPrivateThis
        || decl.isPrivateWithin
        || decl.displayName.isEmpty
      then None
      else Some(decl.symbol)

    }
  }

  /** Default imports in Scala
    *
    * From java.lang, scala._, scala.package._ and scala.Predef._
    */
  def scalaDefaults(using symtab: Symtab): Map[String, List[Symbol]] = {

    val scalaPackageObj = membersOf("scala/package.")
    val predef = membersOf("scala/Predef.")

    val scalaDirectMembers = Macros
      .scalaMembers()
      .filter(name => isIdentifier(name))
      .flatMap(name =>
        val typeSym = Symbol(s"scala/$name#")
        val termSym = Symbol(s"scala/$name.")
        List(typeSym.info, termSym.info).flatten.map(i => name -> i.symbol)
      )

    val javaLangMembers = Macros
      .javaLangMembers()
      .filter(name => isIdentifier(name))
      .flatMap(name =>
        val typeSym = Symbol(s"java/lang/$name#")
        val termSym = Symbol(s"java/lang/$name.")
        List(typeSym.info, termSym.info).flatten.map(i => name -> i.symbol)
      )

    (scalaPackageObj.map(sym => sym.displayName -> sym) ++
      predef.map(sym => sym.displayName -> sym) ++
      scalaDirectMembers ++
      javaLangMembers)
      .foldLeft(HashMap.empty[String, List[Symbol]]) { (acc, entry) =>
        val (name, sym) = entry
        acc.updated(name, acc.getOrElse(name, Nil) :+ sym)
      }
  }
