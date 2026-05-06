package fix

import scalafix.testkit._
import org.scalatest.funsuite.AnyFunSuiteLike
import scala.meta._
import scala.meta.dialects.Scala3

class RuleSuite extends AbstractSemanticRuleSuite with AnyFunSuiteLike {

  // Use this override to compare structure and not syntax
  override def compareContents(obtained: String, expected: String): String = {
    val obt = obtained.parse[Source].get.structure
    val exp = expected.parse[Source].get.structure
    if (obt == exp) ""
    else super.compareContents(obt, exp)
  }

  // runAllTests()

  private val selectedTests = Set(
    // "scala/fix/ImplicitToUsing.scala",
    "scala/fix/FullyQualifiedNames.scala"
  )

  testsToRun
    // .filter(test => selectedTests.contains(test.path.testName))
    .foreach(test => runOn(test))
}
