/*
rule = MetaRule
 */
package fix.tokenrewrite

object TokenRewrite:
  val oldName = 1;
  val text = "oldName;"
  // oldName;
  val result = oldName;
  val sameLine = oldName; val other = 2
  val plainTrue = true
  val plainFalse = false
  val negatedTrue = !true
  val negatedFalse = !false
  val quotedNegation = "!true"
  // !false
