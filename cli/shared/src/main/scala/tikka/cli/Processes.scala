package tikka.cli

import cats.effect.IO

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Starting other programs: the user's editor, `java` for the daemon jar, and the service manager. Behind a trait so
  * tests can record what would run instead of loading a real launchd agent.
  */
trait Processes:
  /** Runs with this terminal's input and output, waiting for it to finish, and returns its exit code. */
  def interactive(command: List[String], extraEnvironment: Map[String, String]): IO[Int]

  /** Runs to completion in `directory` and returns its exit code with stdout and stderr together. */
  def capture(command: List[String], directory: Option[Path]): IO[(Int, String)]

object Processes:
  val system: Processes = new Processes:
    def interactive(command: List[String], extraEnvironment: Map[String, String]): IO[Int] =
      IO.interruptible:
        val builder = ProcessBuilder(command.asJava).inheritIO()
        builder.environment().putAll(extraEnvironment.asJava)
        builder.start().waitFor()

    def capture(command: List[String], directory: Option[Path]): IO[(Int, String)] =
      IO.interruptible:
        val builder = ProcessBuilder(command.asJava).redirectErrorStream(true)
        directory.foreach(path => builder.directory(path.toFile): Unit)
        val process = builder.start()
        val output = String(process.getInputStream.readAllBytes(), UTF_8)
        (process.waitFor(), output)

  /** The first executable called `program` on a `PATH`, the way a shell would find it. */
  def which(program: String, path: Option[String]): Option[Path] =
    path.toList
      .flatMap(_.split(':').toList)
      .filter(_.nonEmpty)
      .map(directory => Path.of(directory, program))
      .find(candidate => Files.isRegularFile(candidate) && Files.isExecutable(candidate))
