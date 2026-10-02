package find

def findAllAndPrint(entry: cs214.Entry): Boolean =
  println(entry.path());
  if entry.isDirectory() && entry.hasChildren() then
    findAllAndPrint(entry.firstChild())
  if entry.hasNextSibling() then findAllAndPrint(entry.nextSibling())
  true

def findByNameAndPrint(entry: cs214.Entry, name: String): Boolean =
  val foundHere =
    if entry.name() == name then
      println(entry.path())
      true
    else false

  val isDirectory = if entry.isDirectory() then true else false
  val foundInChildren =
    if isDirectory then
      entry.hasChildren() && findByNameAndPrint(entry.firstChild(), name)
    else false
  val foundInSibling =
    if entry.hasNextSibling() then
      findByNameAndPrint(entry.nextSibling(), name)
    else false
  foundHere || foundInChildren || foundInSibling

def findEmptyAndPrint(entry: cs214.Entry): Boolean =
  val foundHere =
    if entry.isDirectory() && !entry.hasChildren() then
      println(entry.path())
      true
    else if !entry.isDirectory() && 0 == entry.size() then
      println(entry.path())
      true
    else false
  val foundInChildren =
    if entry.isDirectory() then
      entry.hasChildren() && findEmptyAndPrint(entry.firstChild())
    else false
  val foundInSibling =
    if entry.hasNextSibling() then
      findEmptyAndPrint(entry.nextSibling())
    else false
  foundHere || foundInChildren || foundInSibling

def findFirstByNameAndPrint(entry: cs214.Entry, name: String): Boolean =
  if entry.name().equals(name) then
    println(entry.path())
    return true
  if entry.isDirectory() && entry.hasChildren() &&
      findFirstByNameAndPrint(entry.firstChild(), name)
  then return true
  if entry.hasNextSibling() && findFirstByNameAndPrint(entry.nextSibling(), name)
  then return true
  false
