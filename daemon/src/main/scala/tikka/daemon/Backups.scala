package tikka.daemon

import cats.effect.IO
import cats.syntax.all.*
import tikka.core.Migrations

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The daemon's own backups: a consistent snapshot at startup and every day after.
  *
  * Copying a live WAL-mode file can catch the database and its write-ahead log at different moments; `VACUUM INTO`
  * cannot. The user's own backup tooling then picks these files up.
  */
object Backups:
  val dailiesKept: Int = 7

  private val dailyPrefix = "daily-"

  /** Takes today's snapshot, checks it, and prunes older dailies. A snapshot that fails its check is deleted. */
  def daily(home: Home, day: String): IO[Option[Path]] =
    val destination = home.backups.resolve(s"$dailyPrefix$day.db")
    for
      _ <- IO.blocking(Files.createDirectories(home.backups): Unit)
      _ <- IO.blocking(Files.deleteIfExists(destination): Unit)
      _ <- Migrations.snapshot(home.store, destination)
      check <- Migrations.checkIntegrity(destination)
      verified <- discardIfCorrupt(destination, check)
      _ <- prune(home)
    yield verified

  /** Runs for as long as the daemon does: one snapshot now, then one a day. */
  def schedule(home: Home): IO[Nothing] =
    (today.flatMap(daily(home, _)).attempt *> IO.sleep(24.hours)).foreverM

  def dailies(home: Home): IO[List[Path]] =
    IO.blocking:
      if !Files.isDirectory(home.backups) then Nil
      else
        val listing = Files.list(home.backups)
        try
          listing
            .iterator()
            .asScala
            .filter(_.getFileName.toString.startsWith(dailyPrefix))
            .toList
            .sortBy(_.getFileName.toString)
        finally listing.close()

  /** Only dailies are pruned. Migration snapshots are kept indefinitely: they are the only copy of a schema. */
  private def prune(home: Home): IO[Unit] =
    dailies(home).flatMap: files =>
      files.dropRight(dailiesKept).traverse_(file => IO.blocking(Files.deleteIfExists(file): Unit))

  private def discardIfCorrupt(destination: Path, check: Either[String, Unit]): IO[Option[Path]] =
    check match
      case Right(_) => IO.pure(Some(destination))
      case Left(_)  => IO.blocking(Files.deleteIfExists(destination): Unit).as(None)

  private def today: IO[String] = IO.realTimeInstant.map(_.toString.take(10))
