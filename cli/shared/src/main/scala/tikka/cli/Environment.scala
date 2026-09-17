package tikka.cli

import cats.effect.IO
import cats.effect.std.Console
import fs2.Stream
import tikka.shared.DaemonConfig
import tikka.shared.ProjectKey
import tikka.shared.RepoBinding

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** Everything a command reads from the world outside its arguments. Injected, so tests can drive the whole CLI in
  * process with their own variables, directory, input and output.
  */
final case class Environment(
    variables: Map[String, String],
    workingDirectory: Path,
    console: Console[IO],
    input: Stream[IO, String]
):
  /** `TIKKA_HOME` overrides `~/.tikka` for every command, so a dev daemon and a test home never touch the real store.
    */
  def home: Path =
    variables.get("TIKKA_HOME").map(Path.of(_)).getOrElse(Path.of(variables.getOrElse("HOME", "."), ".tikka"))

  def out(text: String): IO[Unit] = console.println(text)

  def err(text: String): IO[Unit] = console.errorln(text)

/** The settings a command runs with, read once per invocation. */
final case class Settings(home: Path, config: DaemonConfig, binding: Option[Binding]):
  def port: Int = config.port.value

  def baseUrl: String = s"http://127.0.0.1:$port"

  def project: Option[ProjectKey] = binding.map(_.project)

/** A `.tikka` file found by walking up from the working directory, the way git finds `.git`. */
final case class Binding(file: Path, project: ProjectKey)

object Settings:
  def load(environment: Environment): IO[Either[String, Settings]] =
    IO.blocking:
      for
        config <- readConfig(environment.home)
        binding <- findBinding(environment.workingDirectory)
      yield Settings(environment.home, config, binding)

  /** An unreadable config is refused rather than ignored: silently falling back would point at the wrong port. */
  private def readConfig(home: Path): Either[String, DaemonConfig] =
    val file = home.resolve("config.toml")
    if !Files.exists(file) then Right(DaemonConfig.default)
    else
      DaemonConfig.parse(String(Files.readAllBytes(file), UTF_8)).left.map(reason => s"$file cannot be read: $reason")

  private def findBinding(start: Path): Either[String, Option[Binding]] =
    Iterator
      .iterate(Option(start.toAbsolutePath.normalize))(_.flatMap(directory => Option(directory.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .map(_.resolve(".tikka"))
      .find(Files.isRegularFile(_)) match
      case None       => Right(None)
      case Some(file) =>
        RepoBinding
          .parse(String(Files.readAllBytes(file), UTF_8))
          .map(parsed => Some(Binding(file, parsed.project)))
          .left
          .map(reason => s"$file cannot be read: $reason")

  /** Claims name the session holding the issue; without `--as`, `TIKKA_ASSIGNEE` names it, then the OS user. */
  def assignee(environment: Environment, explicit: Option[String]): String =
    explicit
      .orElse(environment.variables.get("TIKKA_ASSIGNEE"))
      .orElse(environment.variables.get("USER"))
      .orElse(environment.variables.get("LOGNAME"))
      .getOrElse("unknown")
