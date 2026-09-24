package find.cs214

/** Represents an entity in the filesystem, which can be either a regular file
  * or a directory.
  */
abstract class Entry:

  /** Returns the name of this entry. */
  def name(): String

  /** Returns the size of this file, in bytes.
    *
    * @throws NotAFileException
    *   if called on a directory (i.e., if `isDirectory` returns `true`).
    */
  def size(): Long

  /** Returns the path to this entry as a string.
    *
    * This is an absolute path if the path originally passed to the `open`
    * function was absolute, or a path relative to the current working directory
    * otherwise.
    */
  def path(): String

  /** Checks whether there is a next entry in the same directory. */
  def hasNextSibling(): Boolean

  /** Returns the next entry in the same directory.
    *
    * @throws NoNextSiblingException
    *   if this is the last entry (i.e., if `hasNextSibling` returns `false`).
    */
  def nextSibling(): Entry

  /** Checks whether this entry is a directory. */
  def isDirectory(): Boolean

  /** Checks whether this directory has children.
    *
    * @throws NotADirectoryException
    *   if called on a file (i.e., if `isDirectory` returns `false`).
    */
  def hasChildren(): Boolean

  /** Returns the first child of this directory.
    *
    * The children are returned in lexicographical (alphabetical) order.
    *
    * @throws NoChildrenException
    *   if the directory has no first child (i.e., if `hasChildren` returns
    *   `false`).
    * @throws NotADirectoryException
    *   if called on a file (i.e., if `isDirectory` returns `false`).
    */
  def firstChild(): Entry

  /** Prints a textual representation of this Entry */
  override def toString: String =
    f"Entry(\"${path()}\")"

class NotADirectoryException extends Exception("This entry is not a directory")
class NotAFileException extends Exception("This entry is not a file")
class NoChildrenException extends Exception("No children found")
class NoNextSiblingException extends Exception("No next sibling found")
