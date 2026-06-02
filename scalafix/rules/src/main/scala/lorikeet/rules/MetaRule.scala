package lorikeet.rules

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.io.Source._
import scala.util.{Try, Success, Failure}
import pureconfig._
import pureconfig.error.ConfigReaderFailures
import lorikeet.core.LorikeetEngine

class MetaRule extends SemanticRule("MetaRule"):
  override def fix(using doc: SemanticDocument): Patch =
    LorikeetEngine.run(None)
