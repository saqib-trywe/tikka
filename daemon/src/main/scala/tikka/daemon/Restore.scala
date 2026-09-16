package tikka.daemon

import cats.effect.IO
import cats.syntax.all.*
import tikka.core.Migrations

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Putting a snapshot back, with the daemon stopped.
  *
  * Nothing is deleted: the store being replaced is moved aside first, so a mistaken restore is itself restorable. The
  * next start migrates the restored store as normal.
  */
object Restore:
  final case class Restored(from: Path, movedTo: Option[Path])

  def run(home: Home, snapshot: Path, at: String): IO[Restored] =
    // The lock refuses while a daemon holds this home, which is what keeps a restore from racing a live writer.
    Lock
      .acquire(home, note = "restore")
      .use: _ =>
        for
          _ <- ensureReadable(snapshot)
          check <- Migrations.checkIntegrity(snapshot)
          _ <- reject(check.isLeft, s"$snapshot failed its integrity check: ${check.left.getOrElse("")}")
          version <- Migrations.currentVersion(snapshot)
          _ <- reject(
            version > Migrations.latest,
            s"$snapshot is at schema v$version, newer than this tikka's v${Migrations.latest}"
          )
          replaced <- moveCurrentAside(home, at)
          _ <- IO.blocking(Files.copy(snapshot, home.store, StandardCopyOption.REPLACE_EXISTING): Unit)
        yield Restored(snapshot, replaced)

  private def moveCurrentAside(home: Home, at: String): IO[Option[Path]] =
    IO.blocking:
      if !Files.exists(home.store) then None
      else
        Files.createDirectories(home.backups): Unit
        val destination = home.backups.resolve(s"pre-restore-$at.db")
        Files.move(home.store, destination, StandardCopyOption.REPLACE_EXISTING): Unit
        // The write-ahead log belongs to the store that just moved; leaving it would corrupt the restored one.
        List("-wal", "-shm").foreach: suffix =>
          Files.deleteIfExists(Path.of(home.store.toString + suffix)): Unit
        Some(destination)

  private def ensureReadable(snapshot: Path): IO[Unit] =
    IO.blocking(Files.isReadable(snapshot)).flatMap(readable => reject(!readable, s"$snapshot cannot be read"))

  private def reject(condition: Boolean, reason: String): IO[Unit] =
    IO.raiseError(IllegalStateException(reason)).whenA(condition)
