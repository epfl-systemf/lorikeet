import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.util.Using

object FeedbackFiles:
  def atomicWrite(path: Path, content: Array[Byte]): Unit =
    Files.createDirectories(path.getParent)
    val temporary =
      Files.createTempFile(path.getParent, "." + path.getFileName, ".tmp")
    try
      Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel =>
          channel.write(ByteBuffer.wrap(content))
          channel.force(true)
      }
      if Files.exists(path) then
        try
          Files.setPosixFilePermissions(
            temporary,
            Files.getPosixFilePermissions(path)
          )
        catch case _: UnsupportedOperationException => ()
      try
        Files.move(
          temporary,
          path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      catch
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temporary)

  def csvField(value: Any): String =
    val string = value.toString
    if string.exists(char =>
        char == ',' || char == '"' || char == '\n' || char == '\r'
      )
    then "\"" + string.replace("\"", "\"\"") + "\""
    else string

