package tikka.core

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import tikka.shared.*
import tikka.shared.Codec.given

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** "I want my data out", and "the daemon will not start", answered by the same command.
  *
  * JSON Lines in the contract's own types, read-only and offline from the daemon jar, so it works with no daemon
  * running and survives schema changes. There is deliberately no import: restoring is putting a snapshot back.
  */
object Export:
  def run(store: Path, destination: Path): IO[Int] =
    Store
      .openReadOnly(store)
      .use: opened =>
        val core = Core(opened, Clock.system)
        for
          projects <- core.projects
          issues <- projects.flatTraverse(project => issuesOf(opened, core, project.key))
          events <- core.allEvents
          lines = projects.map(record("project", _)) ++ issues.map(record("issue", _)) ++ events.map(record("event", _))
          _ <- write(destination, lines)
        yield lines.size

  private def issuesOf(store: Store, core: Core, project: ProjectKey): IO[List[IssueDetail]] =
    store
      .reading(Queries.issuesIn(project))
      .flatMap(_.traverse(record => core.get(record.id, includeEvents = false)))
      .map(_.collect { case Right(view) => view.detail })

  private def record[A: io.circe.Encoder](kind: String, value: A): String =
    Json.obj("type" -> kind.asJson).deepMerge(value.asJson).noSpaces

  private def write(destination: Path, lines: List[String]): IO[Unit] =
    IO.blocking:
      Option(destination.toAbsolutePath.getParent).foreach(parent => Files.createDirectories(parent): Unit)
      Files.write(
        destination,
        lines.map(_ + "\n").mkString.getBytes("UTF-8"),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      ): Unit
