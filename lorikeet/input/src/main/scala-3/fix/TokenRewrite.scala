/*
rule = MetaRule
 */
package fix.tokenrewrite

object TokenRewrite:
  val oldName = 1;
  val text = "oldName;"
  // oldName;
  val result = oldName;
