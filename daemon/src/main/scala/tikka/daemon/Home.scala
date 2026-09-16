package tikka.daemon

import cats.effect.IO
import tikka.shared.DaemonConfig

import java.nio.file.Files
import java.nio.file.Path

/** Everything tikka keeps for one user: the store, its config, the lock, the logs and the snapshots.
  *
  * `TIKKA_HOME` overrides `~/.tikka` for every daemon and CLI command, so developing tikka while its own tickets live
  * in tikka means a second home and port, and tests use a temporary one.
  */
final case class Home(dir: Path, config: DaemonConfig):
  def store: Path = dir.resolve("tikka.db")

  def backups: Path = dir.resolve("backups")

  def lockFile: Path = dir.resolve("daemon.lock")

  def logs: Path = dir.resolve("logs")

  def configFile: Path = Home.configFile(dir)

object Home:
  def configFile(dir: Path): Path = dir.resolve("config.toml")

  def load: IO[Home] = at(default)

  def at(dir: Path): IO[Home] =
    for
      _ <- IO.blocking(Files.createDirectories(dir): Unit)
      config <- readConfig(dir)
    yield Home(dir.toAbsolutePath, config)

  private def default: Path =
    sys.env.get("TIKKA_HOME").map(Path.of(_)).getOrElse(Path.of(sys.props("user.home"), ".tikka"))

  /** A config file that cannot be read stops the daemon rather than being silently ignored: the port must not move. */
  private def readConfig(dir: Path): IO[DaemonConfig] =
    val file = configFile(dir)
    IO.blocking(Files.exists(file))
      .flatMap:
        case false => IO.pure(DaemonConfig.default)
        case true  =>
          IO.blocking(Files.readString(file))
            .flatMap: text =>
              DaemonConfig.parse(text) match
                case Right(config) => IO.pure(config)
                case Left(reason)  => IO.raiseError(IllegalStateException(s"$file cannot be read: $reason"))
