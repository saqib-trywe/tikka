package tikka.daemon

import cats.effect.IO
import cats.syntax.all.*
import tikka.core.Migrations

import java.nio.file.Files

/** Daily snapshots: consistent copies the user's own backup tooling can pick up. */
class BackupsTest extends DaemonFixtures:
  home.test("a daily snapshot is a consistent copy that passes its own integrity check"): value =>
    for
      _ <- seed(value)
      snapshot <- Backups.daily(value, "2026-09-16")
      path = snapshot.getOrElse(fail("expected a snapshot"))
      check <- Migrations.checkIntegrity(path)
      version <- Migrations.currentVersion(path)
    yield
      assert(Files.exists(path), s"$path should exist")
      assertEquals(check, Right(()))
      assertEquals(version, Migrations.latest)

  home.test("seven dailies are kept, and migration snapshots are never pruned"): value =>
    val days = (1 to 10).toList.map(day => f"2026-09-$day%02d")
    for
      _ <- seed(value)
      migration <- Migrations.run(value.store, Some(value.backups), Migrations.all :+ Migration2)
      _ <- days.traverse_(day => Backups.daily(value, day))
      dailies <- Backups.dailies(value)
    yield
      assertEquals(dailies.size, Backups.dailiesKept)
      assertEquals(dailies.map(_.getFileName.toString).head, "daily-2026-09-04.db")
      assert(migration.exists(Files.exists(_)), "the migration snapshot is kept indefinitely")

  home.test("taking today's snapshot again replaces it"): value =>
    for
      _ <- seed(value)
      first <- Backups.daily(value, "2026-09-16")
      second <- Backups.daily(value, "2026-09-16")
      files <- Backups.dailies(value)
    yield
      assertEquals(first, second)
      assertEquals(files.size, 1)

  private val Migration2 = tikka.core.Migration(2, () => "CREATE INDEX issue_by_created ON issue (created)")
