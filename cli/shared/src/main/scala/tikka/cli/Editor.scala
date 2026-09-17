package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import tikka.shared.Render
import tikka.shared.Wire.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** `tikka edit TIK-42` with no flags: the body in `$EDITOR`, written back guarded by the version that was read.
  *
  * A concurrent agent edit therefore surfaces as `stale_version` instead of being silently overwritten, and the edited
  * file is kept so no typing is lost.
  */
object Editor:
  def edit(
      environment: Environment,
      settings: Settings,
      api: Api,
      processes: Processes,
      id: String,
      json: Boolean
  ): IO[ExitCode] =
    api
      .get(id, events = false)
      .flatMap:
        case Left(failure) => Output.failure(environment, json, failure)
        case Right(issue)  =>
          val file = settings.home.resolve("tmp").resolve(s"$id.md")
          for
            _ <- IO.blocking(write(file, issue.body))
            status <- processes.interactive(command(environment, file), Map.empty)
            code <-
              if status != 0 then
                environment
                  .err(s"the editor exited with status $status; nothing was saved (your text is in $file)")
                  .as(ExitCode(1))
              else save(environment, api, issue, file, json)
          yield code

  private def save(environment: Environment, api: Api, issue: IssueOut, file: Path, json: Boolean): IO[ExitCode] =
    IO.blocking(String(Files.readAllBytes(file), UTF_8))
      .flatMap: edited =>
        if edited == issue.body then
          IO.blocking(Files.deleteIfExists(file)).void *> environment.out("no changes").as(ExitCode.Success)
        else
          api
            .update(issue.id, UpdateIn(expectedVersion = Some(issue.version), body = Some(edited)))
            .flatMap:
              case Right(written) =>
                IO.blocking(Files.deleteIfExists(file)).void *> Output.result(environment, json, written)(
                  Render.written
                )
              case Left(failure) =>
                Output.failure(environment, json, failure) <* environment.err(s"your edit is kept in $file")

  /** `$EDITOR` may carry arguments, as `code --wait` does, so it is split on whitespace. */
  def command(environment: Environment, file: Path): List[String] =
    val editor = environment.variables.get("VISUAL").orElse(environment.variables.get("EDITOR")).getOrElse("vi")
    editor.trim.split("\\s+").toList :+ file.toString

  private def write(file: Path, body: String): Unit =
    Files.createDirectories(file.getParent)
    Files.write(file, body.getBytes(UTF_8)): Unit
