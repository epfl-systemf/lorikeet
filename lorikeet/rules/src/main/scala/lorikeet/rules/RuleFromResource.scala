package lorikeet.rules

import scalafix.v1._
import lorikeet.core.LorikeetEngine
import scala.io.Source

/*

Extend this class to create a custom Lorikeet rule from
a configuration resource file. The resource file should be on the classpath.
For example:

class MyRule extends lorikeet.rules.RuleFromResource("MyRule", "/path/to/resource.txt")

 */
abstract class RuleFromResource(
    name: String,
    resourcePath: String
) extends SemanticRule(name):
  def this(resourcePath: String) =
    this("LorikeetRuleFromResource", resourcePath)
  override def fix(using doc: SemanticDocument): Patch =
    val stream = this.getClass.getClassLoader.getResourceAsStream(resourcePath)
    if (stream != null) then
      val config = Source.fromInputStream(stream).getLines().mkString("\n")
      stream.close()
      LorikeetEngine.run(Some(config))
    else
      throw new IllegalArgumentException(
        s"Lorikeet error: Could not find config file at '$resourcePath' on the classpath."
      )
