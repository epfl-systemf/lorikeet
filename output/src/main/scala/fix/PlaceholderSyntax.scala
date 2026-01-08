package fix

object PlaceholderSyntax:
  // Lambda to placeholder syntax
  val words = List("cat", "dog", "elephant")
  words.map(_.length())
  words.foldLeft(0)(_ + _.length())
  // Remain unchanged
  words.foldLeft("")((acc, x) => acc + x)
