package tikka.daemon

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.nio.channels.{FileChannel, FileLock}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

final case class Home(dir: Path, port: Int):
  def store: Path   = dir.resolve("tikka.db")
  def backups: Path = dir.resolve("backups")
  def uiDir: Option[Path] = sys.env.get("TIKKA_UI_DIR").map(Paths.get(_))

object Home:
  val version: String = "0.0.0-skeleton"

  def load: IO[Home] = IO.blocking {
    val dir = sys.env.get("TIKKA_HOME").map(Paths.get(_))
      .getOrElse(Paths.get(sys.props("user.home"), ".tikka"))
    Files.createDirectories(dir)
    val config = dir.resolve("config.toml")
    val port =
      if Files.exists(config) then
        "(?m)^\\s*port\\s*=\\s*(\\d+)".r.findFirstMatchIn(Files.readString(config)).map(_.group(1).toInt)
      else None
    Home(dir.toAbsolutePath, port.getOrElse(7474))
  }

  /** Exclusive lock for the daemon's lifetime; a second daemon on the same home is refused. */
  def lock(home: Home): Resource[IO, Unit] =
    val path = home.dir.resolve("daemon.lock")
    val acquire = IO.blocking {
      val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
      Option(channel.tryLock()) match
        case None =>
          val holder = new String(Files.readAllBytes(path), UTF_8).trim
          channel.close()
          throw IllegalStateException(s"another tikka daemon holds ${path}: $holder")
        case Some(lock) =>
          val info = s"pid=${ProcessHandle.current().pid()} port=${home.port} version=$version\n"
          channel.truncate(0)
          channel.write(java.nio.ByteBuffer.wrap(info.getBytes(UTF_8)), 0)
          (channel, lock)
    }
    Resource.make(acquire)((channel, lock) => IO.blocking { lock.release(); channel.close() }).void
