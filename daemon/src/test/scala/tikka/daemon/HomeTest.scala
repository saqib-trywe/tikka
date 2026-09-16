package tikka.daemon

import cats.effect.IO

import java.nio.file.Files

/** The home is where every daemon and CLI command looks, so its config is read strictly. */
class HomeTest extends DaemonFixtures:
  home.test("the port comes from config.toml, or falls back to the default"): value =>
    for
      fallback <- Home.at(value.dir)
      _ <- IO.blocking(Files.writeString(Home.configFile(value.dir), "port = 9123\n"))
      configured <- Home.at(value.dir)
    yield
      assertEquals(fallback.config.port.value, tikka.shared.DaemonConfig.defaultPort.value)
      assertEquals(configured.config.port.value, 9123)

  home.test("a config file that cannot be read stops the daemon rather than being ignored"): value =>
    for
      _ <- IO.blocking(Files.writeString(Home.configFile(value.dir), "port = \"seven\"\n"))
      failed <- Home.at(value.dir).attempt
    yield assert(failed.isLeft, "a bad config must not silently move the port")

  home.test("the home holds the store, backups, lock and logs"): value =>
    IO.pure:
      assertEquals(value.store.getFileName.toString, "tikka.db")
      assertEquals(value.backups.getFileName.toString, "backups")
      assertEquals(value.lockFile.getFileName.toString, "daemon.lock")
      assertEquals(value.logs.getFileName.toString, "logs")
