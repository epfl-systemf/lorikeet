package lorikeet.rules

import scalafix.v1._
import lorikeet.core.LorikeetEngine

/*

Extend this class to create a custom Lorikeet rule
from a configuration string. For example:

class MyRule extends lorikeet.rules.RuleFromString("MyRule", """...""")

 */
abstract class RuleFromString(
    name: String,
    config: String
) extends SemanticRule(name):
  def this(config: String) = this("LorikeetRuleFromString", config)
  override def fix(using doc: SemanticDocument): Patch =
    LorikeetEngine.run(Some(config))
