package find

import scala.util.Random // Unexpected Import observation.

// Copy this file as a synthetic submission for the find lab.
// It deliberately mixes safe rewrites and review-only observations.
def findByNameAndPrint(entry: cs214.Entry, name: String): Boolean =
  var visits: Int = 0
  val path = { entry.path() }
  val matches = if (entry.name() == name) == true then true else false
  val alsoMatches = !(!(entry.name() == name))
  val impossible = !(entry.name() != name)
  val redundant = (visits + 0) * 1
  visits = redundant

  while visits < 1 do { visits += 1 }
  for item <- List(entry) do println(item.path())

  if matches then { println(path) }
  if impossible && alsoMatches && visits > 0 then return true
  return false

def findEmptyAndPrint(entry: cs214.Entry): Boolean =
  def helper(current: cs214.Entry): Boolean =
    if current.isDirectory() then false else current.size() == 0
  helper(entry)

def helperParcours(F: cs214.Entry => Boolean, E: cs214.Entry): Boolean =
  E.isDirectory() && F(E)
