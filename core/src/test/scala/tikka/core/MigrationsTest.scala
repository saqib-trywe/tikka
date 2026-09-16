package tikka.core

import cats.effect.IO

import java.nio.file.Files
import java.sql.DriverManager
import scala.util.Using

/** Migrations against a real store, including the case that matters: one with data already in it. */
class MigrationsTest extends TempHome, Builders:
  private val addIndex = Migration(2, () => "CREATE INDEX issue_by_created ON issue (created)")

  home.test("a fresh store migrates to the current schema"): directory =>
    for
      snapshot <- Migrations.run(directory.store, Some(directory.backups))
      version <- Migrations.currentVersion(directory.store)
      check <- Migrations.checkIntegrity(directory.store)
    yield
      assertEquals(version, Migrations.latest)
      assertEquals(check, Right(()))
      // Nothing to lose in an empty store, so no snapshot is taken.
      assertEquals(snapshot, None)
      assertEquals(directory.backupFiles, Nil)

  home.test("migrating a store with data snapshots it first, and the snapshot still holds the rows"): directory =>
    for
      issue <- withCore(directory)(core => core.project("TIK").flatMap(core.addTo(_, "First issue")))
      snapshot <- Migrations.run(directory.store, Some(directory.backups), Migrations.all :+ addIndex)
      version <- Migrations.currentVersion(directory.store)
      copy = snapshot.getOrElse(fail("expected a snapshot"))
      snapshotVersion <- Migrations.currentVersion(copy)
      titles <- IO.blocking(titlesIn(copy))
      check <- Migrations.checkIntegrity(copy)
    yield
      assertEquals(issue.render, "TIK-1")
      assertEquals(version, 2)
      assert(Files.exists(copy), s"$copy should exist")
      assertEquals(snapshotVersion, 1, "the snapshot is the store as it was before the migration")
      assertEquals(titles, List("First issue"))
      assertEquals(check, Right(()))

  home.test("a store newer than this tikka is refused, naming both versions"): directory =>
    for
      _ <- Migrations.run(directory.store, Some(directory.backups))
      _ <- IO.blocking(setVersion(directory, 9))
      failed <- Migrations.run(directory.store, Some(directory.backups)).attempt
    yield failed match
      case Left(mismatch: StoreVersionMismatch) =>
        assertEquals(mismatch.storeVersion, 9)
        assertEquals(mismatch.daemonVersion, Migrations.latest)
      case other => fail(s"expected a version mismatch, got $other")

  home.test("an already-migrated store is left alone"): directory =>
    for
      _ <- Migrations.run(directory.store, Some(directory.backups))
      second <- Migrations.run(directory.store, Some(directory.backups))
    yield
      assertEquals(second, None)
      assertEquals(directory.backupFiles, Nil)

  private def titlesIn(path: java.nio.file.Path): List[String] =
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${path.toAbsolutePath}")): connection =>
      Using.resource(connection.createStatement()): statement =>
        Using.resource(statement.executeQuery("SELECT title FROM issue ORDER BY number")): results =>
          Iterator
            .continually(if results.next() then Some(results.getString(1)) else None)
            .takeWhile(_.isDefined)
            .flatten
            .toList

  private def setVersion(directory: java.nio.file.Path, version: Int): Unit =
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${directory.store.toAbsolutePath}")): connection =>
      Using.resource(connection.createStatement()): statement =>
        statement.execute(s"PRAGMA user_version = $version"): Unit
