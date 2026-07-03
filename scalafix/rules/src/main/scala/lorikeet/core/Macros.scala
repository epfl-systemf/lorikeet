package lorikeet.core

object Macros:
  import scala.quoted.*

  /** All members of scala._ */
  inline def scalaMembers(): List[String] = ${ scalaMembersImpl }

  private def scalaMembersImpl(using Quotes): Expr[List[String]] =
    import quotes.reflect._

    val scalaPkg = Symbol.requiredPackage("scala")

    val annotationClass = Symbol.requiredClass("scala.annotation.Annotation")

    def getCleanNames(root: Symbol): List[String] =
      root.declarations
        .filter { sym =>
          val name = sym.name
          !name.contains("$") &&
          !sym.flags.is(Flags.Synthetic) &&
          !sym.flags.is(Flags.Artifact) &&
          (sym.isType || sym.isTerm) &&
          // Filter out annotations if they are Scala-defined
          !(sym.isType && sym.typeRef.derivesFrom(annotationClass))
        }
        .map(_.name)

    val totalMembers = getCleanNames(scalaPkg).distinct.sorted

    Expr(totalMembers)

  /** All members of java.lang */
  inline def javaLangMembers(): List[String] = ${ javaLangMembersImpl }

  private def javaLangMembersImpl(using Quotes): Expr[List[String]] =
    import quotes.reflect._

    val javaLangPkg = Symbol.requiredPackage("java.lang")
    val annotationClass = Symbol.requiredClass("scala.annotation.Annotation")

    def getCleanNames(root: Symbol): List[String] =
      root.declarations
        .filter { sym =>
          val name = sym.name
          !name.contains("$") &&
          !sym.flags.is(Flags.Synthetic) &&
          !sym.flags.is(Flags.Artifact) &&
          (sym.isType || sym.isTerm) &&
          // Filter out annotations if they are Scala-defined
          !(sym.isType && sym.typeRef.derivesFrom(annotationClass))
        }
        .map(_.name)

    val totalMembers = getCleanNames(javaLangPkg).distinct.sorted

    Expr(totalMembers)
