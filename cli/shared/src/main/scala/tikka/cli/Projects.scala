package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import tikka.shared.ProjectKey
import tikka.shared.RepoBinding
import tikka.shared.Wire.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Projects and repository binding. Creating a project is a human act, which is why it lives here and not in MCP. */
object Projects:
  def create(context: Context, key: String, name: String, json: Boolean): IO[ExitCode] =
    context.api
      .createProject(ProjectIn(key, name))
      .flatMap(Output.handle(context.environment, json)(_)(project => s"${project.key}  ${project.name}"))

  def list(context: Context, json: Boolean): IO[ExitCode] =
    context.api.projects.flatMap(
      Output.handle(context.environment, json)(_)(projects =>
        if projects.isEmpty then "no projects yet; create one with `tikka project new KEY \"Name\"`"
        else projects.map(project => s"${project.key}  ${project.name}").mkString("\n")
      )
    )

  /** Binds the working directory to a project: `.tikka` for the CLI and stdio proxy, and the project-scoped MCP entry
    * for HTTP clients. It never creates a project unless asked with `--new`, so a typo cannot become a permanent key.
    */
  def init(context: Context, key: String, newName: Option[String], json: Boolean): IO[ExitCode] =
    ProjectKey.parse(key) match
      case Left(reason)   => Output.usage(context.environment, reason)
      case Right(project) =>
        val created = newName.fold(IO.pure(Right(()): Either[Failure, Unit]))(name =>
          context.api.createProject(ProjectIn(key, name)).map(_.map(_ => ()))
        )
        created.flatMap:
          case Left(failure) => Output.failure(context.environment, json, failure)
          case Right(_)      =>
            context.api.projects.flatMap:
              case Left(failure) => Output.failure(context.environment, json, failure)
              case Right(projects) if !projects.exists(_.key == key) =>
                context.environment
                  .err(
                    s"there is no project $key. Create it with `tikka project new $key \"Name\"`, " +
                      s"or bind and create in one step with `tikka init $key --new \"Name\"`."
                  )
                  .as(ExitCode(1))
              case Right(_) => bind(context, project)

  private def bind(context: Context, project: ProjectKey): IO[ExitCode] =
    val directory = context.environment.workingDirectory
    val binding = directory.resolve(".tikka")
    val url = s"${context.settings.baseUrl}/mcp?project=${project.value}"
    val command = List("claude", "mcp", "add", "--scope", "project", "--transport", "http", "tikka", url)
    for
      _ <- IO.blocking(Files.write(binding, RepoBinding.render(RepoBinding(project)).getBytes(UTF_8)))
      _ <- context.environment.out(s"wrote $binding")
      _ <- Processes.which("claude", context.environment.variables.get("PATH")) match
        case None         => snippet(context, url)
        case Some(claude) =>
          context.processes
            .capture(claude.toString :: command.tail, Some(directory))
            .flatMap: (status, output) =>
              if status == 0 then context.environment.out(s"added the tikka MCP server for Claude Code ($url)")
              else context.environment.err(output.trim) *> snippet(context, url)
    yield ExitCode.Success

  private def snippet(context: Context, url: String): IO[Unit] =
    context.environment.out(
      s"""To connect an MCP client, add this server to its configuration:
         |  {"mcpServers": {"tikka": {"type": "http", "url": "$url"}}}""".stripMargin
    )
