package tikka.daemon

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all.*
import tikka.core.Clock
import tikka.core.Export
import tikka.core.Migrations
import tikka.shared.BuildVersion

import java.nio.file.Path

/** The daemon jar's entry point.
  *
  * `export` and `restore` are jar operations rather than HTTP calls, so they answer "the daemon will not start". The
  * surfaces themselves arrive with the HTTP and MCP adapters; for now `run` opens the store and reports on it.
  */
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] = args match
    case "run" :: Nil             => start
    case "export" :: file :: Nil  => exportTo(Path.of(file))
    case "restore" :: file :: Nil => restoreFrom(Path.of(file))
    case "--version" :: Nil | Nil => IO.println(s"tikka daemon ${BuildVersion.current.value}").as(ExitCode.Success)
    case other                    =>
      IO.println(s"unknown arguments: ${other.mkString(" ")}") *>
        IO.println("usage: tikka-daemon [run | export <file> | restore <snapshot> | --version]").as(ExitCode(2))

  private def start: IO[ExitCode] =
    Home.load.flatMap: home =>
      Lock
        .acquire(home)
        .use: _ =>
          for
            check <- Migrations.checkIntegrity(home.store)
            _ <- refuseCorrupt(home, check)
            moved <- Migrations.run(home.store, Some(home.backups))
            _ <- moved.traverse(path => IO.println(s"migrated, snapshot at $path"))
            code <- serve(home)
          yield code

  /** Runs until stopped, taking daily snapshots in the background. */
  private def serve(home: Home): IO[ExitCode] =
    (Daemon.resource(home) <* Backups.schedule(home).background).use: daemon =>
      IO.println(
        s"tikka daemon ${BuildVersion.current.value} serving http://${daemon.server.addressIp4s} (store ${home.store})"
      ) *> IO.never.as(ExitCode.Success)

  private def exportTo(file: Path): IO[ExitCode] =
    Home.load.flatMap: home =>
      Export.run(home.store, file).flatMap(lines => IO.println(s"wrote $lines records to $file")).as(ExitCode.Success)

  private def restoreFrom(snapshot: Path): IO[ExitCode] =
    for
      home <- Home.load
      at <- Clock.system.now
      done <- Restore.run(home, snapshot, at.value.replace(":", "-"))
      _ <- done.movedTo.traverse(path => IO.println(s"previous store moved to $path"))
      _ <- IO.println(s"restored ${done.from} to ${home.store}")
    yield ExitCode.Success

  /** A failing store refuses to start, naming the newest passing snapshot and the way back. */
  private def refuseCorrupt(home: Home, check: Either[String, Unit]): IO[Unit] = check match
    case Right(_)     => IO.unit
    case Left(detail) =>
      Backups
        .dailies(home)
        .flatMap: snapshots =>
          val advice = snapshots.lastOption.fold("no snapshot was found to restore from")(path =>
            s"restore the newest snapshot with: tikka daemon restore $path"
          )
          IO.raiseError(IllegalStateException(s"${home.store} failed its integrity check ($detail); $advice"))
