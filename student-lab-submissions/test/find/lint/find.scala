package find

object LintExample:
  var counter: Int = 0

  def simplifiedCondition(ready: Boolean): Boolean =
    if ready then false else !ready
