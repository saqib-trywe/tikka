package tikka.core

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** A throwaway TIKKA_HOME per test, under `target/` so a stray one is swept up with the build output. */
trait TempHome extends CatsEffectSuite:
  val home: FunFixture[Path] = FunFixture[Path](
    setup = test =>
      val root = Path.of("target", "test-homes").toAbsolutePath
      Files.createDirectories(root)
      Files.createTempDirectory(root, test.name.replaceAll("[^A-Za-z0-9]+", "-").take(40) + "-")
    ,
    teardown = directory => delete(directory)
  )

  private def delete(directory: Path): Unit =
    val entries = Files.walk(directory)
    try entries.sorted(Comparator).forEach(path => Files.deleteIfExists(path): Unit)
    finally entries.close()

  private object Comparator extends java.util.Comparator[Path]:
    def compare(left: Path, right: Path): Int = right.compareTo(left)

  extension (directory: Path)
    def store: Path = directory.resolve("tikka.db")
    def backups: Path = directory.resolve("backups")

    def backupFiles: List[Path] =
      if !Files.isDirectory(backups) then Nil
      else
        val listing = Files.list(backups)
        try listing.iterator().asScala.toList
        finally listing.close()

  /** A migrated store at the home's path, with a core service over it, closed again afterwards. */
  def withCore[A](directory: Path, clock: Clock = Clock.system)(use: Core => IO[A]): IO[A] =
    Migrations.run(directory.store, Some(directory.backups)) *>
      Store.open(directory.store).use(store => use(Core(store, clock)))
