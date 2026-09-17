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
  /** One compute thread. sttp's curl backend calls `curl_easy_perform` on a compute thread, and declares it without
    * Scala Native's `@blocking`, so a garbage collection started by any other runtime thread during a request waits for
    * that call forever: on Linux the CLI intermittently hung, then aborted. With one worker, nothing else runs Scala
    * code while a request is in flight. A command makes one or two requests, so it loses nothing.
    */
  override protected def computeWorkerThreadCount: Int = 1

  def run(arguments: List[String]): IO[ExitCode] =
    val environment = Environment(
      variables = System.getenv().asScala.toMap,
      workingDirectory = Path.of("").toAbsolutePath,
      console = Console[IO],
      input = fs2.io.stdin[IO](4096).through(text.utf8.decode).through(text.lines)
    )
    Cli.run(arguments, environment, Processes.system, HttpBackend.resource, line => IO.println(line))
