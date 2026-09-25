# Find: lab summary

Implement a simplified Unix `find` command in Scala 3. Submit only `find.scala`.

## Learning goal

Practice structural recursion over a filesystem tree represented by `cs214.Entry`.
Each entry can have a first child and a next sibling. The expected traversal is
depth-first and pre-order: handle the current entry first, then its children,
then its siblings.

## Required functions

Implement these functions, each returning `true` when it printed at least one
matching path and `false` otherwise:

- `findAllAndPrint(entry)`: print every file and directory.
- `findByNameAndPrint(entry, name)`: print entries whose name exactly matches.
- `findBySizeEqAndPrint(entry, size)`: print files with exactly `size` bytes.
- `findBySizeGeAndPrint(entry, minSize)`: print files with at least `minSize` bytes.
- `findEmptyAndPrint(entry)`: print empty files and empty directories.

Print exactly one path per line and no other output. A file is empty when its
size is zero; a directory is empty when it has no children. Guard `Entry`
operations appropriately: `size` is invalid on directories, while `hasChildren`
and `firstChild` are invalid on files; `firstChild` also requires children, and
`nextSibling` requires a next sibling.

## Scope

The mandatory work should use the supplied `cs214.Entry` interface and
structural recursion; built-in filesystem traversal and external libraries do
not satisfy the tests. The optional, ungraded bonus implements
`findFirstByNameAndPrint`, which prints only the first matching name. This is an
early course exercise, before later abstraction and higher-order-function
material.
