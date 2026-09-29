package fix.tokenrewrite

object TokenRewrite:
  val newName = 1
  val text = "oldName;"
  // oldName;
  val result = newName
  val plainTrue = true
  val plainFalse = false
  val negatedTrue = false
  val negatedFalse = true
  val quotedNegation = "!true"
  // !false
