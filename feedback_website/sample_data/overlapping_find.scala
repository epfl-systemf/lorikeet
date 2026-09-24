package find

import scala.util.Random

trait Entry:
  def isDirectory(): Boolean
  def hasChildren(): Boolean
  def firstChild(): Entry
  def hasNextSibling(): Boolean
  def nextSibling(): Entry
  def name(): String
  def path(): String
  def size(): Long

object OverlappingFind:
  def findByName(entry: Entry, name: String, minSize: Long): Boolean =
    var matching = false

    val inChildren =
      if entry.isDirectory() then
        if entry.hasChildren() then
          name.equals(entry.name())
        else
          false
      else
        false

    val noChildren =
      if entry.hasChildren() then false else true

    if !entry.isDirectory() && entry.size() >= minSize then
      println(entry.path())
      return true

    if entry.isDirectory() then
      if entry.hasChildren() then
        println(entry.firstChild().path())

    var current = entry
    while current.hasNextSibling() do
      current = current.nextSibling()
      if current.name() == name && !current.isDirectory() then
        println(current.path())
        matching = true

    matching | inChildren

  def helperParcours(F: Entry => Boolean, E: Entry): Boolean =
    F(E)

  def applyPredicate(predicate: Entry => Boolean, entry: Entry): Boolean =
    predicate(entry)

  def returnPosition(entry: Entry): Boolean =
    var found = entry.hasChildren()
    return found

  def trailingLiteral(entry: Entry): Boolean =
    println(entry.path());
    true
