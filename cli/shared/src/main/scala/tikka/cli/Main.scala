package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.std.Console
import fs2.text

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The `tikka` binary: the real environment, the platform's HTTP backend, and standard input as lines. */
object Main extends IOApp:
  def run(arguments: List[String]): IO[ExitCode] =
    val environment = Environment(
      variables = System.getenv().asScala.toMap,
      workingDirectory = Path.of("").toAbsolutePath,
      console = Console[IO],
      input = fs2.io.stdin[IO](4096).through(text.utf8.decode).through(text.lines)
    )
    Cli.run(arguments, environment, Processes.system, HttpBackend.resource, line => IO.println(line))
