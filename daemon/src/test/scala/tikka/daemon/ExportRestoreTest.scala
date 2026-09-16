package tikka.daemon

import cats.effect.IO
import io.circe.parser.parse
import tikka.core.Export
import tikka.core.Migrations

import java.nio.file.Files

/** "I want my data out" and "the daemon will not start", both answered without a daemon. */
class ExportRestoreTest extends DaemonFixtures:
  home.test("the export writes one JSON line per project, issue and event, with no daemon running"): value =>
    val file = value.dir.resolve("export.jsonl")
    for
      issue <- seed(value)
      count <- Export.run(value.store, file)
      lines <- IO.blocking(Files.readAllLines(file).toArray.toList.map(_.toString))
      kinds = lines.flatMap(line => parse(line).toOption.flatMap(_.hcursor.get[String]("type").toOption))
      issueLine = lines.find(_.contains("\"type\":\"issue\""))
    yield
      assertEquals(count, lines.size)
      assertEquals(kinds.count(_ == "project"), 1)
      assertEquals(kinds.count(_ == "issue"), 1)
      assertEquals(kinds.count(_ == "event"), 1)
      assert(issueLine.exists(_.contains(s""""id":"${issue.render}"""")), issueLine)
      assert(issueLine.exists(_.contains("\"comments\"")), "an issue carries its comments")

  home.test("a restore moves the current store aside and puts the snapshot back"): value =>
    for
      _ <- seed(value, "Original issue")
      snapshot <- Backups.daily(value, "2026-09-16").map(_.getOrElse(fail("expected a snapshot")))
      _ <- seed(value, "Later issue")
      restored <- Restore.run(value, snapshot, "2026-09-16T10-00-00.000Z")
      file = value.dir.resolve("after.jsonl")
      _ <- Export.run(value.store, file)
      lines <- IO.blocking(Files.readString(file))
    yield
      assert(restored.movedTo.exists(Files.exists(_)), "nothing is deleted, so a mistaken restore is restorable")
      assert(lines.contains("Original issue"), "the snapshot's rows are back")
      assert(!lines.contains("Later issue"), "the store written after the snapshot was moved aside")

  home.test("a restore is refused while a daemon holds the home"): value =>
    for
      _ <- seed(value)
      snapshot <- Backups.daily(value, "2026-09-16").map(_.getOrElse(fail("expected a snapshot")))
      refused <- Lock.acquire(value).use(_ => Restore.run(value, snapshot, "now").attempt)
    yield assert(refused.left.exists(_.isInstanceOf[HomeInUse]), refused)

  home.test("a snapshot from a newer tikka is refused"): value =>
    for
      _ <- seed(value)
      snapshot <- Backups.daily(value, "2026-09-16").map(_.getOrElse(fail("expected a snapshot")))
      _ <- IO.blocking(setVersion(snapshot, Migrations.latest + 5))
      refused <- Restore.run(value, snapshot, "now").attempt
    yield assert(refused.left.exists(_.getMessage.contains("newer than this tikka")), refused)

  private def setVersion(path: java.nio.file.Path, version: Int): Unit =
    scala.util.Using.resource(java.sql.DriverManager.getConnection(s"jdbc:sqlite:${path.toAbsolutePath}")):
      connection =>
        scala.util.Using.resource(connection.createStatement())(_.execute(s"PRAGMA user_version = $version"): Unit)
