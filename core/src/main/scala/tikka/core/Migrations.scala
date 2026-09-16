package tikka.core

import cats.effect.IO

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import scala.io.Source
import scala.util.Using

/** The store is a version behind or ahead of the daemon that opened it. */
final class StoreVersionMismatch(val storeVersion: Int, val daemonVersion: Int)
    extends RuntimeException(
      s"this store is at schema v$storeVersion, but this tikka only knows v$daemonVersion — upgrade tikka"
    )

/** The store failed `PRAGMA quick_check`. */
final class StoreCorrupt(val path: Path, val detail: String)
    extends RuntimeException(s"$path failed its integrity check: $detail")

/** Numbered, forward-only migrations tracked by SQLite's `user_version`.
  *
  * A non-empty store is snapshotted with `VACUUM INTO` before anything is applied, and a store newer than this daemon
  * is refused rather than opened. Both exist because the store is the permanent record of agents' work.
  */
/** One numbered step. The SQL is read lazily so a missing script fails when it is applied, not at class load. */
final case class Migration(version: Int, sql: () => String)

object Migrations:
  // The migration runner reaches SQLite through DriverManager, which only knows the driver once it is loaded.
  Class.forName("org.sqlite.JDBC"): Unit

  /** Migrations are never edited once released; a change is a new entry. */
  val all: Vector[Migration] = Vector(Migration(1, () => read("V1.sql")))

  val latest: Int = all.last.version

  def currentVersion(path: Path): IO[Int] = withConnection(path)(readVersion)

  /** Brings the store to [[latest]], taking a snapshot into `backups` first when there is anything to lose.
    *
    * Returns the snapshot it took, if it took one.
    */
  def run(path: Path, backups: Option[Path], migrations: Vector[Migration] = all): IO[Option[Path]] =
    withConnection(path): connection =>
      val target = migrations.last.version
      val from = readVersion(connection)
      if from > target then throw StoreVersionMismatch(from, target)
      else if from == target then None
      else
        // The snapshot must happen with no statement in flight: VACUUM INTO fails with "SQL statements in progress".
        val snapshot = Option.when(from > 0)(backups.map(snapshotTo(connection, _, from))).flatten
        migrations.filter(_.version > from).foreach(migration => applyScript(connection, migration))
        snapshot

  /** `PRAGMA quick_check`, run at startup and after every snapshot. */
  def checkIntegrity(path: Path): IO[Either[String, Unit]] =
    withConnection(path): connection =>
      val outcome = firstRow(connection, "PRAGMA quick_check")(_.getString(1)).getOrElse("no result")
      if outcome == "ok" then Right(()) else Left(outcome)

  /** An atomic copy of the live store: consistent even while a daemon is writing, which a file copy is not. */
  def snapshot(path: Path, destination: Path): IO[Path] =
    withConnection(path): connection =>
      vacuumInto(connection, destination)
      destination

  private def snapshotTo(connection: Connection, backups: Path, from: Int): Path =
    Files.createDirectories(backups)
    val stamp = java.time.Instant.now().toString.replace(":", "-")
    val destination = backups.resolve(s"$stamp-v$from.db")
    vacuumInto(connection, destination)
    destination

  private def vacuumInto(connection: Connection, destination: Path): Unit =
    execute(connection, s"VACUUM INTO '${destination.toAbsolutePath.toString.replace("'", "''")}'")

  private def applyScript(connection: Connection, migration: Migration): Unit =
    val sql = migration.sql()
    connection.setAutoCommit(false)
    try
      // A fresh statement per command: sqlite-jdbc finalizes a statement's pointer as it reuses it, and a reused
      // Statement object then fails with "the prepared statement has been finalized".
      (statements(sql) :+ s"PRAGMA user_version = ${migration.version}").foreach(execute(connection, _))
      connection.commit()
    catch
      case error: Throwable =>
        connection.rollback()
        throw error
    finally connection.setAutoCommit(true)

  private def execute(connection: Connection, sql: String): Unit =
    Using.resource(connection.createStatement())(_.execute(sql): Unit)

  /** Splits a script into statements. Comment lines go first: prose in a comment may contain a semicolon, and a
    * fragment holding nothing but a comment compiles to no statement at all.
    */
  private def statements(sql: String): List[String] =
    sql.linesIterator
      .filterNot(_.trim.startsWith("--"))
      .mkString("\n")
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  private def read(script: String): String =
    val resource = getClass.getClassLoader.getResourceAsStream(s"tikka/migrations/$script")
    if resource == null then throw IllegalStateException(s"migration $script is missing from the daemon jar")
    Using.resource(Source.fromInputStream(resource, "UTF-8"))(_.mkString)

  private def readVersion(connection: Connection): Int =
    firstRow(connection, "PRAGMA user_version")(_.getInt(1)).getOrElse(0)

  /** Closing the statement closes its result set; closing both finalizes the statement twice in sqlite-jdbc. The result
    * set must be gone before `VACUUM INTO`, which refuses while any statement is in progress.
    */
  private def firstRow[A](connection: Connection, sql: String)(read: java.sql.ResultSet => A): Option[A] =
    Using.resource(connection.createStatement()): statement =>
      val results = statement.executeQuery(sql)
      if results.next() then Some(read(results)) else None

  private def withConnection[A](path: Path)(use: Connection => A): IO[A] =
    IO.blocking:
      Option(path.getParent).foreach(parent => Files.createDirectories(parent): Unit)
      Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${path.toAbsolutePath}"))(use)
