# Find

In this lab, you will apply your knowledge of structural recursion to a practical scenario: navigating the entries of a file system. This hands-on application will show you the power of recursion and how it can be used to solve real-world problems.

## Obtaining the lab files

Start by ensuring you've completed the [tools setup](/info/tools-setup/), the [example degrees-converter lab]({scaffold}/labs/degrees/) and started [the exercises on structural recursion](/exercises/week/1/): you will need the notions covered in these resources to complete this lab, starting with how to *clone the lab repository*, which is covered in the [example degrees-converter lab]({scaffold}/labs/degrees/).

<div class="note">

Once you've cloned the project, you can open the project in your preferred code editor. For this course, we recommend using [Visual Studio Code](https://code.visualstudio.com) with the *Metals* extension for Scala. Please refer to our [tools setup guide](/info/tools-setup/) for installation and configuration instructions.

To open Visual Studio Code, you have two options:

**Graphical Method:**

- Launch Visual Studio Code.
- Use the "Open Folder" option to navigate to and select the folder that `git clone` created (this folder will contain a `build.sbt` file).

**Terminal Method:**

- Navigate to the folder that `git clone` created in your terminal (by typing `cd find` after running the `git clone […]` command).
- Run the command `code .`.

The `code` command will start Visual Studio Code, and the ` .` indicates that it should open the current directory.

</div>

## Introduction

The [`find` command](https://man7.org/linux/man-pages/man1/find.1.html) is a powerful utility in Unix-like operating systems, allowing users to search for files and directories based on various criteria. It searches by name, size, type (file or directory), modification date, and many other properties. Additionally, `find` can perform actions on the files and directories it locates, such as displaying their names, deleting them, or running other commands on them.

Consider the following directory structure inside the `find/src` directory, as displayed by the macOS Finder:

<img src="www-assets/files.png" class="medium" />

Using a terminal, we can use the `find` command to search for files and directories within this directory structure. The following command, for example, searches for files ending with `.scala` with a size bigger than 1200 bytes in the `src/main/` directory:

```console
$ find src/main/ -name "*.scala" -size +1200c
src/main/scala/find/cs214/FindOutputParser.scala
src/main/scala/find/cs214/Entry.scala
src/main/scala/find/cli/main.scala
```

In this lab, you'll implement a simplified version of the `find` command in Scala.

<div class="note">

We refer to the `find` command in Unix-like operating systems here. There is also a command named `find` in Windows, but it is not the same as the one we are implementing in this lab. If you want to try the Unix `find` command in Windows, you might want to either:

* [Use](https://stackoverflow.com/questions/21438556/finding-files-in-a-git-bash-terminal) the "Git BASH" terminal that comes with Git on Windows, or
* download a [Windows build](https://gnuwin32.sourceforge.net/packages/findutils.htm) of GNU FindUtils, or
* use [WSL](https://learn.microsoft.com/en-us/windows/wsl/install).

</div>

## Structure

A call to `find` usually begins with the _path_ where the search should start, followed by one or more _filters_, and ends with an _action_:

```
find  src/main/  -name "*.scala" -size +1200c  -print
      ---------  ----------------------------  ------
        path                filter             action
```

For example, in `find src/main/ -name "*.scala" -size +1200c -print`:

- The path is `src/main/`.
- The filters are `-name "*.scala"` and `-size +1200c`.
- The action is `-print` (this is the default action, so it can be omitted).

In this lab:

- We will only support one filter at a time, and only the following filters:
  - No filter: finds all files and directories.
  - `-name "filename"`: finds files and directories with the exact name `filename`.
  - `-size Xc`: finds files whose size is _exactly_ `X` bytes.
  - `-size +Xc`: finds files whose size is _at least_ `X` bytes.
  - `-empty`: finds empty files and directories.
- We will only support one action: printing the results. As this is the default action, we'll always omit it from the command: no need to specify `-print`.

You will implement five functions, one for each filter. Each function will receive a `cs214.Entry` object as its argument and return a boolean indicating whether results were found. This `Entry` object represents the file or directory where the search should start. `cs214.Entry` is an abstraction of a file or directory in the file system that we wrote for you, explained in the next section.

For each function you implement in this lab, please adhere to the following guidelines:
- Print one path per line, without any other content.
- Print entries in the following order:
  1. the entry itself (if it matches),
  2. then its children,
  3. then its siblings.
  Going down into the children first is called a [depth-first search](https://en.wikipedia.org/wiki/Depth-first_search), and printing the entry first makes the whole algorithm a [pre-order traversal](https://en.wikipedia.org/wiki/Tree_traversal)
- Return a `Boolean`: `true` if at least one result was found, `false` otherwise.

The entry point of the program (usually called the `main` function) is the `cs214find` function in `src/main/scala/find/cli/main.scala`.  It is already implemented for you. It parses the command-line arguments, creates a `cs214.Entry` object, and invokes the relevant function based on the filter specified in the command line arguments. _You do not need to read nor to understand this file_.

## The `cs214.Entry` class

The `cs214.Entry` class represents a file or directory in the file system. Each entry has a reference to its first child and to its next sibling. For example, the following directory structure:

```
food/
food/fruits/
food/fruits/strawberry.txt
food/fruits/tomato.txt
food/vegetables/
food/vegetables/tomato.txt
```

would be represented as 6 entries with the following relationships:

<svg viewBox="0 0 211 261" style="max-width: 16rem">
  <text style="fill: light-dark(rgb(51, 51, 51),rgba(190, 190, 190, 1)); font-family: 'IBM Plex Mono'; font-size: 16px; white-space: pre;" transform="matrix(1, 0, 0, 1, -100.06900787353516, -142.65835571289062)" x="100.06900024414062" y="159.16000366210938">food/<tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan>    fruits/<tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan>        strawberry.txt<tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan>        tomato.txt<tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan>    vegetables/<tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan><tspan x="100.06900024414062" dy="1em">​</tspan>        tomato.txt</text>
  <text style="fill: rgb(129, 1, 122); font-family: Arial, sans-serif; font-size: 12px; font-style: italic; white-space: pre; opacity: 0.9;" x="29.092" y="40.771">firstChild</text>
  <text style="fill: rgb(129, 1, 122); font-family: Arial, sans-serif; font-size: 12px; font-style: italic; white-space: pre; opacity: 0.9;" x="68.998" y="89.929">firstChild</text>
  <text style="fill: rgb(29, 129, 1); font-family: Arial, sans-serif; font-size: 12px; font-style: italic; white-space: pre; opacity: 0.9;" x="45.85" y="181.208">nextSibling</text>
  <path d="M 4.919 20.366 H 43.799 L 43.799 15.866 L 53.799 20.866 L 43.799 25.866 L 43.799 21.366 H 4.919 V 20.366 Z" style="fill: rgb(129, 1, 122);" transform="matrix(0.6799349784851074, 0.7332720160484314, -0.7332720160484314, 0.6799349784851074, 15.875221252441406, 3.0716540813446045)"/>
  <path d="M 4.919 20.366 H 43.799 L 43.799 15.866 L 53.799 20.866 L 43.799 25.866 L 43.799 21.366 H 4.919 V 20.366 Z" style="fill: rgb(129, 1, 122);" transform="matrix(0.6799349784851074, 0.7332720160484314, -0.7332720160484314, 0.6799349784851074, 55.55797576904297, 50.945865631103516)"/>
  <path d="M 43.169 67.925 H 161.198 L 161.198 63.425 L 171.198 68.425 L 161.198 73.425 L 161.198 68.925 H 43.169 V 67.925 Z" style="fill: rgb(29, 129, 1);" transform="matrix(0, 1, -1, 0, 111.593597, 25.256031)"/>
  <path d="M 80.694 115.414 H 101.116 L 101.116 110.914 L 111.116 115.914 L 101.116 120.914 L 101.116 116.414 H 80.694 V 115.414 Z" style="fill: rgb(29, 129, 1);" transform="matrix(0, 1, -1, 0, 196.608383, 35.219666)"/>
  <path d="M 35.076 229.226 L 73.956 229.226 L 73.956 224.726 L 83.956 229.726 L 73.956 234.726 L 73.956 230.226 L 35.076 230.226 L 35.076 229.226 Z" style="fill: rgb(129, 1, 122); transform-box: fill-box; transform-origin: 50% 50%;" transform="matrix(0.679935, 0.733272, -0.733272, 0.679935, 0, -0.00001)"/>
  <text style="fill: rgb(29, 129, 1); font-family: Arial, sans-serif; font-size: 12px; font-style: italic; white-space: pre; opacity: 0.9;" x="86.771" y="138.909">nextSibling</text>
  <text style="fill: rgb(129, 1, 122); font-family: Arial, sans-serif; font-size: 12px; font-style: italic; white-space: pre; opacity: 0.9;" x="71.209" y="234.836">firstChild</text>
</svg>

Each `cs214.Entry` has 8 methods: `.name()`, `.size()`, `.path()`, `.hasNextSibling()`, `.nextSibling()`, `.isDirectory()`, `.hasChildren()` and `.firstChild()`.

Additionally, the function <code>cs214.open(<var>path</var>)</code> is provided to create an `Entry` object from a _path_ string.

In the following REPL session, we demonstrate the usage of `cs214.Entry` using the `food` example directory depicted above:

```scala repl
scala> val food = find.cs214.open("example-dir/food")
val food: find.cs214.Entry = Entry("example-dir/food")

scala> food.name()
val res0: String = food

scala> food.path()
val res1: String = example-dir/food

scala> food.isDirectory()
val res2: Boolean = true

scala> food.hasChildren()
val res3: Boolean = true

scala> val fruits = food.firstChild()
val fruits: find.cs214.Entry = Entry("example-dir/food/fruits")

scala> fruits.isDirectory()
val res4: Boolean = true

scala> fruits.hasChildren()
val res5: Boolean = true

scala> val strawberry = fruits.firstChild()
val strawberry: find.cs214.Entry = Entry("example-dir/food/fruits/strawberry.txt")

scala> strawberry.isDirectory()
val res6: Boolean = false

scala> strawberry.hasNextSibling()
val res7: Boolean = true

scala> strawberry.nextSibling()
val res8: find.cs214.Entry = Entry("example-dir/food/fruits/tomato.txt")

scala> fruits.hasNextSibling()
val res9: Boolean = true

scala> val vegetables = fruits.nextSibling()
val vegetables: find.cs214.Entry = Entry("example-dir/food/vegetables")

scala> vegetables.isDirectory()
val res10: Boolean = true

scala> vegetables.hasChildren()
val res11: Boolean = true

scala> vegetables.firstChild()
val res12: find.cs214.Entry = Entry("example-dir/food/vegetables/tomato.txt")

scala> vegetables.hasNextSibling()
val res13: Boolean = false
```

Note that some of these methods may throw exceptions:

- `entry.size()` will throw an exception if `entry` is a directory.
- `entry.nextSibling()` will throw an exception if there is no next sibling.
- `entry.hasChildren()` will throw an exception if `entry` is not a directory
- `entry.firstChild()` will throw an exception if `entry` is a file, or if `entry` is a directory with no children.

## Testing

Some tests are provided for you in `src/test/scala/find/FindTest.scala`. You can run them by executing the following command from the root of the project (the `find/` directory that you cloned):

```console
$ sbt testFull
```

You don't need to understand the code in `FindTest.scala`: you'll learn more about testing in a later lesson. However, if you want to understand why a specific test fails, it may be useful to read through the comments in the file.

<div class="note">

One of the tests will appear in yellow and will be marked as `ignored`. This is expected and related to [the last section](#bonus-findfirstbynameandprint-) of this lab. It does not count towards the grade.

</div>

<div class="warning">

Our tests depend on your code using the `cs214.Entry` object, so using built-in Scala or Java functions or external libraries to traverse the file system will not work.

</div>

## Running

You can execute your command from the SBT shell using the `run` SBT command. Here's an example of running your `find` command with a path and no filter. After implementing `findAllAndPrint`, you should see the following output:

```console
$ sbt
sbt:find> run src/
[info] running find.cli.cs214find src
src
src/main
src/main/scala
src/main/scala/find
src/main/scala/find/cli
src/main/scala/find/cli/main.scala
src/main/scala/find/cs214
src/main/scala/find/cs214/Entry.scala
src/main/scala/find/cs214/FindOutputParser.scala
src/main/scala/find/cs214/MockEntry.scala
src/main/scala/find/cs214/OSLibEntry.scala
src/main/scala/find/cs214/open.scala
src/main/scala/find/find.scala
src/main/scala/playground.worksheet.sc
src/test
src/test/scala
src/test/scala/find
src/test/scala/find/FindTest.scala
[success] Total time: 5 s, completed Sep 9, 2023, 9:57:06 PM
sbt:find> run src/test/
[info] running find.cli.cs214find src/test
src/test
src/test/scala
src/test/scala/find
src/test/scala/find/FindTest.scala
[success] Total time: 0 s, completed Sep 9, 2023, 9:59:22 PM
```

<div class="note">

On Windows, the backslashes (`\`) should be escaped when running a command from the SBT shell. I.e., you should double every backslash:

```
sbt:find> run C:\\Users\\Ada
```

The same is true in worksheets or other Scala code:

```scala
val entry = cs214.open("C:\\Users\\Ada")
```

</div>

## Generating an executable

You can also create a standalone executable that can be run from the command-line without SBT. To achieve this, use the `pack` command inside the SBT shell:

```console
$ sbt
sbt:find> pack
...
[success] Total time: 0 s, completed Sep 9, 2023, 9:58:07 PM
sbt:find> exit
```

This command produces the executable `cs214find` inside the `target/pack/bin` directory, which you can run like any other regular command line program. After implementing `findByNameAndPrint`, you should observe the following output:

```console
$ target/pack/bin/cs214find src -name Entry.scala
src/main/scala/find/cs214/Entry.scala
```

## Debugging

To debug your code, remember the tools from the [example degrees converter lab]({scaffold}/labs/degrees/):

- Use worksheets or the REPL to quickly evaluate expressions and inspect their results.
- Use the `test` and `testOnly` commands to run the tests.

There is an existing worksheet at `src/main/scala/playground.worksheet.sc`. If your setup is working correctly, this is what you should see when opening it, after some time:

![](www-assets/worksheet.png)

## Implementation

You have five functions to implement: `findAllAndPrint`, `findByNameAndPrint`, `findBySizeEqAndPrint`, `findBySizeGeAndPrint`, `findEmptyAndPrint`.  Each of them implements a different `find` filter (no filter, `-name`, `-size`, `-size +`, and `-empty`) by printing matching files and directories and returning a Boolean indicating whether any results were found.  More precisely:

1. Each function takes one _entry_ (a starting point for the search).
2. Some functions additionally take a _filter_ (`name`, `size`, or `minSize`).
3. Each function must recursively check all files that are reachable from the initial _entry_ parameter.
4. Each function should print the path (`.path()`) of any entry that matches the search criterion, and return a boolean indicating whether any matching entries were found.

<div class="note">

The initial _entry_ parameters come either from the command line or from the tests.  To pass the tests, you may assume the following two things:

1. The initial entry will be a directory (`.isDirectory()` will be true)
2. The initial entry will have no siblings (`.hasNextSibling()` will be false)

That said, the most natural implementation does not need these facts and works just as well when the initial input has siblings or is a file.  In that case, the correct behavior would be to print any matching entries among the siblings of the initial entry.  Our test suite (and hence our grading system) does not test this case.

</div>

### 1. `findAllAndPrint`

Implement the <code>findAllAndPrint(<var>entry</var>)</code> function. It should traverse the filesystem recursively by invoking the relevant methods on the `cs214.Entry` object, print the paths of all files and directories it finds, and return a `Boolean` indicating whether any files or directories were printed.

To print the paths, you can use the [`println`](https://www.scala-lang.org/files/archive/api/3.3.1/scala/Console$.html#println-fffff71f) function, which displays a string followed by a newline on the terminal's standard output.

`println` can be placed on the line directly preceding any Scala expression; for example, the following function prints `"Hello!"` and returns 3:

```scala
def hello(): Int =
    println("Hello!")
    3
```

<details class="hint">
<summary>Hint</summary>

Can `findAllAndPrint` ever return `false`?

</details>

### 2. `findByNameAndPrint`

Implement the <code>findByNameAndPrint(<var>entry</var>, <var>name</var>)</code> function. It should search for entries (files or directories) that have the given name, print their paths, and return a `Boolean` indicating whether any entries were found.

Unlike the real `find` command, wildcards are not supported in `findByNameAndPrint`: if the user searches for `Doc*.txt`, it will look for a file or directory named exactly `Doc*.txt` and not files starting with `Doc` and ending with `.txt`.

### 3. `findBySizeEqAndPrint`

Implement the <code>findBySizeEqAndPrint(<var>entry</var>, <var>size</var>)</code> function. It should search for files that have exactly the given size, print their paths, and return a `Boolean` indicating whether any files were found.

`findBySizeEqAndPrint` should disregard directories.

### 4. `findBySizeGeAndPrint`

Implement the <code>findBySizeGeAndPrint(<var>entry</var>, <var>minSize</var>)</code> function. Unlike the previous function that searches for files of a specific size, `findBySizeGeAndPrint` locates files larger than or equal to the given size.

### 5. `findEmptyAndPrint`

Lastly, implement the <code>findEmptyAndPrint(<var>entry</var>)</code> function. It should search for entries (files or directories) that are empty, print their paths, and return a `Boolean` indicating whether any results were found.

A file is considered empty if it has size `0`, and a directory is empty if it contains no children.

## Submitting your work

If all the tests run successfully, then you are done: congratulations on completing your first CS-214 lab!  And next time you run out of disk space on your laptop, you'll have the perfect tool at hand to scan your drive for excessively large files:

```console
$ target/pack/bin/cs214find /home/ada/ -size +52428800c
/home/ada/.config/discord/0.0.28/modules/discord_voice/discord_voice.node
/home/ada/.local/share/Steam/steamapps/common/Overcooked! 2/Overcooked2_Data/resources.assets
/home/ada/.local/share/Steam/steamapps/common/Proton 7.0/proton_dist.tar
/home/ada/.local/share/Steam/ubuntu12_32/steam-runtime.tar.xz
/home/ada/.cache/coursier/v1/https/github.com/adoptium/temurin17-binaries/releases/download/jdk-17%252B35/OpenJDK17-jdk_x64_linux_hotspot_17_35.tar.gz
…
```

To turn in your lab, you must submit one file to Moodle:

- `find.scala`

On the assignment page:

1. Click "Add submission".
2. Drop the `find.scala` file in the "File submissions" box.
3. Click "Save changes".
4. Click "Submit assignment".
5. Confirm that you respected the rules by checking the box next to "This assignment is my own work".
6. Click "Continue".

     <img src="www-assets/confirm.png" class="small">

Double-check that:

- Your submission status is "Submitted for grading".
- You have submitted the `find.scala` file and no other files.
- You have received a confirmation email with the subject "You have submitted your assignment submission for Lab: Find".

![](www-assets/submit.png)

{% include ../poll-reminder.md %}

## Conclusion

Take a moment to look at the functions you've implemented in this lab. Do you notice any similarities or patterns in your code? Any repetitive structures or blocks?

Recognizing patterns and redundancy is a key step towards writing more efficient and modular code.  In future lessons, you'll learn about new concepts and how they can be used to abstract common patterns in code, making your programs more concise, readable, and maintainable.

We will return to this `find` lab at a later stage and see how we can refactor and modularize our `find` functions to reduce redundancy and enhance flexibility.

## Bonus: `findFirstByNameAndPrint` 🔥

**This part is optional and not graded.**

Sometimes you don't care to see all results — just the first one.  How would you need to change your code to return just the first result?  To answer this question, implement the <code>findFirstByNameAndPrint(<var>entry</var>)</code> function. Similarly to `findByNameAndPrint`, it should list the files with the given name, but instead of printing them all, it should only print the first one. This mirrors the behavior of `find path -name "filename" -print -quit`.

There is an elegant solution to this question that uses only concepts from the first lecture of CS-214, and its implementation looks very similar to `findByNameAndPrint`.  We'll see later how you can write a single function to cover both cases.

There is an extra test at the bottom of `FindTest.scala` that you can enable to test your implementation.

