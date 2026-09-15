package tikka.daemon

import cats.effect.{IO, Resource}
import cats.effect.std.Mutex
import doobie.*
import doobie.implicits.*
import java.nio.file.Files
import java.sql.DriverManager
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.Properties

/** Two transactors: every write goes through one serialized IMMEDIATE-transaction connection. */
final case class Store(read: Transactor[IO], write: Transactor[IO], writeLock: Mutex[IO]):
  def writing[A](program: ConnectionIO[A]): IO[A] = writeLock.lock.surround(program.transact(write))
  def reading[A](program: ConnectionIO[A]): IO[A] = program.transact(read)

object Store:
  /** Forward-only migrations, indexed by the user_version they produce. */
  val migrations: Vector[String] = Vector(
    """
    CREATE TABLE project (
      key TEXT PRIMARY KEY CHECK (length(key) BETWEEN 2 AND 10 AND key GLOB '[A-Z]*' AND key NOT GLOB '*[^A-Z0-9]*'),
      name TEXT NOT NULL,
      next_number INTEGER NOT NULL DEFAULT 1 CHECK (next_number >= 1)
    );
    CREATE TABLE issue (
      project TEXT NOT NULL REFERENCES project(key),
      number INTEGER NOT NULL CHECK (number >= 1),
      title TEXT NOT NULL CHECK (length(trim(title)) > 0),
      created TEXT NOT NULL,
      PRIMARY KEY (project, number)
    );
    -- The place mention edges will live; deliberately left empty by the skeleton.
    CREATE TABLE mention (
      from_project TEXT NOT NULL, from_number INTEGER NOT NULL,
      to_project TEXT NOT NULL, to_number INTEGER NOT NULL,
      PRIMARY KEY (from_project, from_number, to_project, to_number)
    );
    INSERT INTO project (key, name) VALUES ('SKL', 'Skeleton');
    """,
    // v2 exists only to exercise the snapshot-before-migrate path on a non-empty store.
    "CREATE INDEX issue_created ON issue (created);",
  )

  private def props(write: Boolean): Properties =
    val p = Properties()
    p.setProperty("journal_mode", "WAL")
    p.setProperty("synchronous", "FULL")
    p.setProperty("busy_timeout", "5000")
    p.setProperty("foreign_keys", "true")
    if write then p.setProperty("transaction_mode", "IMMEDIATE")
    p

  /** Snapshot, then apply pending migrations in one transaction; refuse a store newer than we know. */
  def migrate(home: Home): IO[String] = IO.blocking {
    val url  = s"jdbc:sqlite:${home.store}"
    val conn = DriverManager.getConnection(url, props(write = true))
    try
      val current = { val st = conn.createStatement(); val rs = st.executeQuery("PRAGMA user_version"); rs.next(); val v = rs.getInt(1); rs.close(); st.close(); v }
      val known   = migrations.size
      if current > known then
        throw IllegalStateException(s"store is at schema v$current but this daemon only knows v$known; refusing to open it")
      else if current == known then s"schema v$current, up to date"
      else
        val snapshot =
          if current == 0 then "no snapshot (empty store)"
          else
            Files.createDirectories(home.backups)
            val stamp  = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            val target = home.backups.resolve(s"$stamp-v$current.db")
            conn.createStatement().execute(s"VACUUM INTO '${target}'")
            s"snapshot $target"
        conn.setAutoCommit(false)
        migrations.drop(current).foreach(_.split(";").map(_.trim).filter(_.nonEmpty).foreach(stmt => conn.createStatement().executeUpdate(stmt)))
        conn.createStatement().execute(s"PRAGMA user_version = $known")
        conn.commit()
        s"migrated v$current -> v$known ($snapshot)"
    finally conn.close()
  }

  def resource(home: Home): Resource[IO, Store] =
    val url = s"jdbc:sqlite:${home.store}"
    val read = Transactor.fromDriverManager[IO]("org.sqlite.JDBC", url, props(write = false), None)
    val writerConn = Resource.make(IO.blocking(DriverManager.getConnection(url, props(write = true))))(c => IO.blocking(c.close()))
    for
      conn  <- writerConn
      mutex <- Resource.eval(Mutex[IO])
    yield Store(read, Transactor.fromConnection[IO](conn, None), mutex)
