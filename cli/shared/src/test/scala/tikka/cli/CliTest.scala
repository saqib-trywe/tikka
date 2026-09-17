package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import munit.CatsEffectSuite
import sttp.client4.SttpClientException
import tikka.shared.DaemonConfig
import tikka.shared.ProjectKey
import tikka.shared.Wire.RowOut

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** The CLI's own rules, needing no daemon: parsing, settings, ordering and the service definitions. Runs on the JVM and
  * on Scala Native.
  */
class CliTest extends CatsEffectSuite:
  private val scratch = FunFixture[Path](
    setup = test =>
      val root = Path.of("target", "cli-scratch").toAbsolutePath
      Files.createDirectories(root)
      Files.createTempDirectory(root, test.name.replaceAll("[^A-Za-z0-9]+", "-").take(30) + "-")
    ,
    teardown = _ => ()
  )

  private def run(
      arguments: List[String],
      directory: Path,
      variables: Map[String, String] = Map.empty
  ): IO[(ExitCode, Recorded)] =
    for
      recorded <- Recorded(
        Map("HOME" -> directory.toString, "TIKKA_HOME" -> directory.resolve("home").toString) ++ variables,
        directory
      )
      code <- Cli.run(arguments, recorded.environment, Processes.system, HttpBackend.resource, _ => IO.unit)
    yield (code, recorded)

  scratch.test("--version prints the version and succeeds"): directory =>
    for
      (code, recorded) <- run(List("--version"), directory)
      out <- recorded.out
    yield
      assertEquals(code, ExitCode.Success)
      assert(out.startsWith("tikka "), out)

  scratch.test("an unknown command or a missing argument is a usage error, exit 2"): directory =>
    for
      (unknown, _) <- run(List("frobnicate"), directory)
      (missing, _) <- run(List("show"), directory)
      (badClose, recorded) <- run(List("close", "TIK-1", "-m", "why"), directory)
      err <- recorded.err
    yield
      assertEquals(unknown, ExitCode(2))
      assertEquals(missing, ExitCode(2))
      assertEquals(badClose, ExitCode(2))
      assert(err.contains("usage: tikka close"), err)

  scratch.test("with nothing listening, a command exits 3 and says how to start the daemon"): directory =>
    for
      _ <- IO.blocking(write(directory.resolve("home").resolve("config.toml"), "port = 1\n"))
      (code, recorded) <- run(List("search", "ready"), directory)
      err <- recorded.err
    yield
      assertEquals(code, ExitCode(3))
      assert(err.contains("tikka daemon start"), err)

  scratch.test("the binding is found by walking up from the working directory"): directory =>
    val nested = directory.resolve("repo").resolve("src").resolve("deep")
    for
      _ <- IO.blocking:
        Files.createDirectories(nested)
        write(directory.resolve("repo").resolve(".tikka"), "project = \"TIK\"\n")
      recorded <- Recorded(Map("TIKKA_HOME" -> directory.resolve("home").toString), nested)
      settings <- Settings.load(recorded.environment)
    yield
      assertEquals(settings.map(_.project.map(_.value)), Right(Some("TIK")))
      assertEquals(settings.map(_.port), Right(DaemonConfig.defaultPort.value))

  scratch.test("an unreadable config is refused rather than ignored"): directory =>
    for
      _ <- IO.blocking(write(directory.resolve("home").resolve("config.toml"), "port = \"seven\"\n"))
      recorded <- Recorded(Map("TIKKA_HOME" -> directory.resolve("home").toString), directory)
      settings <- Settings.load(recorded.environment)
    yield assert(settings.isLeft, settings)

  test("the assignee is --as, then TIKKA_ASSIGNEE, then the user"):
    Recorded(Map("TIKKA_ASSIGNEE" -> "saqib/wf-1", "USER" -> "saqib"), Path.of(".")).map: recorded =>
      assertEquals(Settings.assignee(recorded.environment, Some("explicit")), "explicit")
      assertEquals(Settings.assignee(recorded.environment, None), "saqib/wf-1")
      assertEquals(Settings.assignee(recorded.environment.copy(variables = Map("USER" -> "saqib")), None), "saqib")

  test("a refused connection is recognised on both platforms"):
    val request = sttp.client4.basicRequest.get(sttp.model.Uri.unsafeParse("http://127.0.0.1:1/"))
    assert(Api.unreachable(SttpClientException.ConnectException(request, RuntimeException("refused"))))
    // Curl on Scala Native reports it as a plain RuntimeException naming the curl code.
    assert(Api.unreachable(RuntimeException("Command failed with status COULDNT_CONNECT")))
    assert(!Api.unreachable(RuntimeException("something else")))

  test("bulk close takes children before their parents, keeping query order within a depth"):
    def row(id: String, parent: Option[String]): RowOut =
      RowOut(id, id, "open", None, None, Nil, parent, blocked = false, rank = 1.0, updated = "")
    val ordered = Issues.leavesFirst(
      List(row("TIK-1", None), row("TIK-2", Some("TIK-1")), row("TIK-3", Some("TIK-2")), row("TIK-4", Some("TIK-1")))
    )
    assertEquals(ordered.map(_.id), List("TIK-3", "TIK-2", "TIK-4", "TIK-1"))

  test("the editor command honours VISUAL, then EDITOR with its arguments, then vi"):
    val file = Path.of("/tmp/TIK-1.md")
    Recorded(Map("EDITOR" -> "code --wait"), Path.of(".")).map: recorded =>
      assertEquals(Editor.command(recorded.environment, file), List("code", "--wait", "/tmp/TIK-1.md"))
      assertEquals(
        Editor.command(recorded.environment.copy(variables = Map("VISUAL" -> "nano")), file),
        List("nano", "/tmp/TIK-1.md")
      )
      assertEquals(Editor.command(recorded.environment.copy(variables = Map.empty), file), List("vi", "/tmp/TIK-1.md"))

  test("the launchd agent runs the jar with the home set, restarts it, and logs under the home"):
    val home = Path.of("/Users/someone/.tikka")
    val command =
      DaemonControl.jarCommand(Path.of("/opt/jdk/bin/java"), home.resolve("lib/tikka-daemon.jar"), List("run"))
    val plist = DaemonControl.launchdPlist("dev.tikka.daemon", command, home, home.resolve("logs/daemon.log"))
    assert(plist.contains("<string>/opt/jdk/bin/java</string>"), plist)
    assert(plist.contains("<string>--enable-native-access=ALL-UNNAMED</string>"), plist)
    assert(plist.contains("<key>TIKKA_HOME</key>\n    <string>/Users/someone/.tikka</string>"), plist)
    assert(plist.contains("<key>KeepAlive</key>\n  <true/>"), plist)
    assert(plist.contains("<string>/Users/someone/.tikka/logs/daemon.log</string>"), plist)

  test("the systemd unit runs the jar with the home set and restarts on failure"):
    val home = Path.of("/home/someone/.tikka")
    val command = DaemonControl.jarCommand(Path.of("/usr/bin/java"), home.resolve("lib/tikka-daemon.jar"), List("run"))
    val unit = DaemonControl.systemdUnit(command, home, home.resolve("logs/daemon.log"))
    assert(unit.contains("Environment=TIKKA_HOME=/home/someone/.tikka"), unit)
    assert(
      unit.contains(
        "ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED -jar /home/someone/.tikka/lib/tikka-daemon.jar run"
      ),
      unit
    )
    assert(unit.contains("Restart=on-failure"), unit)

  test("each home gets its own service, and the managers are driven with the right commands"):
    val settings = Settings(Path.of("/repo/.tikka-dev"), DaemonConfig.default, None)
    Recorded(Map("HOME" -> "/Users/someone", "TIKKA_HOME" -> "/repo/.tikka-dev"), Path.of(".")).map: recorded =>
      val java = Path.of("/usr/bin/java")
      val mac = DaemonControl.service(DaemonControl.Platform.MacOs, recorded.environment, settings, java, "501")
      val linux = DaemonControl.service(DaemonControl.Platform.Linux, recorded.environment, settings, java, "1000")
      assertEquals(
        mac.map(_.file.toString),
        Right("/Users/someone/Library/LaunchAgents/dev.tikka.daemon.tikka-dev.plist")
      )
      assertEquals(
        mac.map(_.install),
        Right(
          List(
            List(
              "launchctl",
              "bootstrap",
              "gui/501",
              "/Users/someone/Library/LaunchAgents/dev.tikka.daemon.tikka-dev.plist"
            )
          )
        )
      )
      assertEquals(
        mac.map(_.restart),
        Right(List("launchctl", "kickstart", "-k", "gui/501/dev.tikka.daemon.tikka-dev"))
      )
      assertEquals(linux.map(_.file.toString), Right("/Users/someone/.config/systemd/user/tikka-tikka-dev.service"))
      assertEquals(linux.map(_.stop), Right(List("systemctl", "--user", "stop", "tikka-tikka-dev")))
      assert(
        DaemonControl.service(DaemonControl.Platform.Unsupported, recorded.environment, settings, java, "0").isLeft
      )

  test("the default home uses the plain service name"):
    val settings = Settings(
      Path.of("/Users/someone/.tikka"),
      DaemonConfig.default,
      Some(Binding(Path.of("/repo/.tikka"), ProjectKey.parse("TIK").toOption.get))
    )
    Recorded(Map("HOME" -> "/Users/someone"), Path.of(".")).map: recorded =>
      assertEquals(DaemonControl.label(settings, recorded.environment), "dev.tikka.daemon")

  private def write(file: Path, text: String): Unit =
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(UTF_8)): Unit
