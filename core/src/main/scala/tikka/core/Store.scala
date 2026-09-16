package tikka.core

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Mutex
import doobie.*
import doobie.implicits.*

import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/** The SQLite store, reached through two transactors.
  *
  * Every write goes through one connection, serialized by a mutex and opened `IMMEDIATE`, so "check the invariants,
  * then write" can never interleave with another writer and `SQLITE_BUSY` never arises. Reads run beside it under WAL.
  */
final case class Store(path: Path, read: Transactor[IO], write: Transactor[IO], writeLock: Mutex[IO]):
  def writing[A](program: ConnectionIO[A]): IO[A] = writeLock.lock.surround(program.transact(write))

  def reading[A](program: ConnectionIO[A]): IO[A] = program.transact(read)

object Store:
  def open(path: Path): Resource[IO, Store] =
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    for
      writer <- Resource.make(IO.blocking(DriverManager.getConnection(url, properties(writing = true))))(connection =>
        IO.blocking(connection.close())
      )
      lock <- Resource.eval(Mutex[IO])
    yield Store(
      path,
      Transactor.fromDriverManager[IO]("org.sqlite.JDBC", url, properties(writing = false), None),
      Transactor.fromConnection[IO](writer, None),
      lock
    )

  /** Opens the store without writing to it, which works whether or not a daemon is running: under WAL a reader and the
    * daemon's writer coexist. Used by the offline export.
    */
  def openReadOnly(path: Path): Resource[IO, Store] =
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val readOnly = properties(writing = false)
    val transactor = Transactor.fromDriverManager[IO]("org.sqlite.JDBC", url, readOnly, None)
    Resource.eval(Mutex[IO]).map(lock => Store(path, transactor, transactor, lock))

  private def properties(writing: Boolean): Properties =
    val properties = Properties()
    properties.setProperty("journal_mode", "WAL")
    // A committed claim survives power loss. About a millisecond per write, invisible at tikka's write rate.
    properties.setProperty("synchronous", "FULL")
    properties.setProperty("busy_timeout", "5000")
    properties.setProperty("foreign_keys", "true")
    if writing then properties.setProperty("transaction_mode", "IMMEDIATE"): Unit
    properties
