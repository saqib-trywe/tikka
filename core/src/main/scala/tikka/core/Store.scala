package tikka.core

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Mutex
import cats.effect.std.Queue
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/** The SQLite store, reached through one writer and a small pool of readers.
  *
  * Every write goes through one connection, serialized by a mutex and opened `IMMEDIATE`, so "check the invariants,
  * then write" can never interleave with another writer and `SQLITE_BUSY` never arises. Reads run beside it under WAL.
  */
final case class Store(path: Path, readers: Resource[IO, Transactor[IO]], write: Transactor[IO], writeLock: Mutex[IO]):
  def writing[A](program: ConnectionIO[A]): IO[A] = writeLock.lock.surround(program.transact(write))

  def reading[A](program: ConnectionIO[A]): IO[A] = readers.use(program.transact)

object Store:
  /** How many reads may be in flight at once. Reads are short, local and mostly served from page cache; this is about
    * not paying to open a connection on each one, not about parallelism.
    */
  private val readerCount = 4

  def open(path: Path): Resource[IO, Store] =
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    for
      writer <- connection(url, writing = true)
      readers <- pool(url, readerCount)
      lock <- Resource.eval(Mutex[IO])
    yield Store(path, readers, Transactor.fromConnection[IO](writer, None), lock)

  /** Opens the store without writing to it, which works whether or not a daemon is running: under WAL a reader and the
    * daemon's writer coexist. Used by the offline export.
    */
  def openReadOnly(path: Path): Resource[IO, Store] =
    val url = s"jdbc:sqlite:${path.toAbsolutePath}"
    val unused = Transactor.fromDriverManager[IO]("org.sqlite.JDBC", url, properties(writing = false), None)
    for
      readers <- pool(url, 1)
      lock <- Resource.eval(Mutex[IO])
    yield Store(path, readers, unused, lock)

  /** A fixed set of read connections, handed out one at a time and returned when the read finishes.
    *
    * `Transactor.fromDriverManager` opens a connection — and applies every one of its pragmas — for each read, which a
    * local daemon answering in milliseconds notices. Holding them open instead is safe under WAL: doobie begins and
    * commits around each use, so none of these sits on a read transaction and blocks checkpointing.
    */
  private def pool(url: String, size: Int): Resource[IO, Resource[IO, Transactor[IO]]] =
    for
      transactors <- List
        .fill(size)(connection(url, writing = false).map(Transactor.fromConnection[IO](_, None)))
        .sequence
      idle <- Resource.eval(Queue.bounded[IO, Transactor[IO]](size))
      _ <- Resource.eval(transactors.traverse_(idle.offer))
    yield Resource.make(idle.take)(idle.offer)

  private def connection(url: String, writing: Boolean): Resource[IO, java.sql.Connection] =
    Resource.make(IO.blocking(DriverManager.getConnection(url, properties(writing))))(open =>
      IO.blocking(open.close())
    )

  private def properties(writing: Boolean): Properties =
    val properties = Properties()
    properties.setProperty("journal_mode", "WAL")
    // A committed claim survives power loss. About a millisecond per write, invisible at tikka's write rate.
    properties.setProperty("synchronous", "FULL")
    properties.setProperty("busy_timeout", "5000")
    properties.setProperty("foreign_keys", "true")
    if writing then properties.setProperty("transaction_mode", "IMMEDIATE"): Unit
    properties
