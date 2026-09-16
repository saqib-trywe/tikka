package tikka.daemon

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import tikka.shared.BuildVersion

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Another process already holds this home's lock. */
final class HomeInUse(val holder: String) extends RuntimeException(s"another tikka process holds this home: $holder")

/** One daemon per home, enforced by an exclusive lock held for its lifetime.
  *
  * The port alone would not do: a second daemon configured differently would open the same store and contend for the
  * single writer. The file records who holds it, so the refusal can name them and `status` can read it.
  */
object Lock:
  def acquire(home: Home, note: String = "daemon"): Resource[IO, Unit] =
    Resource
      .make(IO.blocking(take(home, note)))((channel, lock) =>
        IO.blocking:
          lock.release()
          channel.close()
      )
      .void

  /** What the lock file says, whether or not anyone still holds it. */
  def holder(home: Home): IO[Option[String]] =
    IO.blocking:
      Option.when(Files.exists(home.lockFile))(Files.readString(home.lockFile).trim).filter(_.nonEmpty)

  private def take(home: Home, note: String): (FileChannel, FileLock) =
    val channel = FileChannel.open(
      home.lockFile,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE
    )
    // A lock held by another process comes back as null; one held by this JVM throws instead.
    val taken =
      try Option(channel.tryLock())
      catch case _: OverlappingFileLockException => None
    taken match
      case None =>
        val existing = Files.readString(home.lockFile).trim
        channel.close()
        throw HomeInUse(if existing.isEmpty then "an unnamed process" else existing)
      case Some(lock) =>
        val details =
          s"pid=${ProcessHandle.current().pid()} port=${home.config.port.value} " +
            s"version=${BuildVersion.current.value} holding=$note\n"
        channel.truncate(0): Unit
        channel.write(ByteBuffer.wrap(details.getBytes(UTF_8)), 0): Unit
        (channel, lock)
