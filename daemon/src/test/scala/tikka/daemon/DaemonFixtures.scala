package tikka.daemon

import cats.effect.IO
import munit.CatsEffectSuite
import tikka.core.Clock
import tikka.core.Core
import tikka.core.Migrations
import tikka.core.Store
import tikka.shared.*

import java.nio.file.Files
import java.nio.file.Path

/** A throwaway home per test, under `target/`, plus enough store to have something to lose. */
trait DaemonFixtures extends CatsEffectSuite:
  val actor: Actor = Actor(Surface.Daemon, None)

  val home: FunFixture[Home] = FunFixture[Home](
    setup = test =>
      val root = Path.of("target", "test-homes").toAbsolutePath
      Files.createDirectories(root)
      val directory = Files.createTempDirectory(root, test.name.replaceAll("[^A-Za-z0-9]+", "-").take(40) + "-")
      Home(directory, DaemonConfig.default)
    ,
    teardown = value => delete(value.dir)
  )

  /** A migrated store holding one project and one issue. */
  def seed(value: Home, titleText: String = "The first issue"): IO[IssueId] =
    Migrations.run(value.store, Some(value.backups)) *>
      Store
        .open(value.store)
        .use: store =>
          val core = Core(store, Clock.system)
          val key = ProjectKey.parse("TIK").fold(sys.error, identity)
          for
            _ <- core.createProject(key, "Tikka")
            title <- IO.fromEither(Title.parse(titleText).left.map(IllegalArgumentException(_)))
            made <- core.create(CreateIssue(Some(key), title, None, Nil, None, Nil, None, Some("first")), None, actor)
          yield made.fold(error => sys.error(error.toString), _.row.id)

  private def delete(directory: Path): Unit =
    val entries = Files.walk(directory)
    try entries.sorted((left, right) => right.compareTo(left)).forEach(path => Files.deleteIfExists(path): Unit)
    finally entries.close()
