package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.monovore.decline.Command
import com.monovore.decline.Opts
import sttp.client4.Backend
import tikka.shared.BuildVersion

/** The command tree. Parsing turns arguments into an action; running it needs settings and a connection. */
object Cli:
  type Action = Context => IO[ExitCode]

  /** Runs one invocation: 0 on success, 1 for a domain rejection, 2 for a usage error, 3 when the daemon is down. */
  def run(
      arguments: List[String],
      environment: Environment,
      processes: Processes,
      backend: Resource[IO, Backend[IO]],
      write: String => IO[Unit]
  ): IO[ExitCode] =
    command(write).parse(arguments, environment.variables) match
      case Left(help) if help.errors.isEmpty => environment.out(help.toString).as(ExitCode.Success)
      case Left(help)                        => environment.err(help.toString).as(ExitCode(2))
      case Right(action)                     =>
        Settings
          .load(environment)
          .flatMap:
            case Left(problem)   => environment.err(problem).as(ExitCode(2))
            case Right(settings) =>
              backend.use(connection =>
                action(Context(environment, settings, Api(connection, settings), processes, connection))
              )

  private val json = Opts.flag("json", "Print the structured result as JSON instead of text.").orFalse

  private val comment = Opts.option[String]("comment", "A comment explaining the write.", short = "m").orNone

  private val why = Opts.option[String]("comment", "Why, in a sentence or two. Required.", short = "m")

  private val as = Opts
    .option[String]("as", "Who holds the issue: name your session. Defaults to $TIKKA_ASSIGNEE, then your user name.")
    .orNone

  private val issue = Opts.argument[String]("ID")

  private val placement = (
    Opts.option[Double]("rank", "An explicit rank.").orNone,
    Opts.option[String]("before", "Place just before this issue.").orNone,
    Opts.option[String]("after", "Place just after this issue.").orNone
  ).mapN(Placement.apply)

  private val labels =
    Opts.options[String]("label", "A label; repeat for more. On edit, --label=-name removes one.").orEmpty

  private val blockedBy =
    Opts.options[String]("blocked-by", "An issue this one waits on; on edit, --blocked-by=-ID removes one.").orEmpty

  private val bodyFile = Opts.option[String]("body-file", "Read the body from this file, or from stdin with -.").orNone

  /** Commands that talk to the daemon also check its version, concurrently, and warn once on a mismatch. */
  private def checked(action: Action): Action = context =>
    (action(context), context.api.meta).parTupled.flatMap:
      case (code, Right(meta)) if meta.version != BuildVersion.current.value =>
        context.environment
          .err(
            s"warning: the daemon is version ${meta.version} but this CLI is ${BuildVersion.current.value}; " +
              "restart the daemon after upgrading"
          )
          .as(code)
      case (code, _) => IO.pure(code)

  private val search: Opts[Action] =
    (
      Opts.argument[String]("QUERY").orNone,
      Opts.option[String]("cursor", "Continue from a previous page's cursor.").orNone,
      Opts.option[Int]("limit", "Page size, at most 200.").orNone,
      json
    ).mapN((query, cursor, limit, asJson) => checked(Issues.search(_, query.getOrElse(""), cursor, limit, asJson)))

  private val show: Opts[Action] =
    (issue, Opts.flag("events", "Include the latest 50 events.").orFalse, json)
      .mapN((id, events, asJson) => checked(Issues.show(_, id, events, asJson)))

  private val create: Opts[Action] =
    (
      Opts.argument[String]("TITLE"),
      Opts.option[String]("project", "The project, when this directory is not bound to one.").orNone,
      bodyFile,
      labels,
      Opts.option[String]("parent", "The parent issue.").orNone,
      blockedBy,
      placement,
      comment,
      json
    ).mapN((title, project, body, labelled, parent, blockers, place, note, asJson) =>
      checked(Issues.create(_, title, project, body, labelled, parent, blockers, place, note, asJson))
    )

  private val edit: Opts[Action] =
    (
      issue,
      Opts.option[String]("title", "A new title.").orNone,
      bodyFile,
      labels,
      Opts.option[String]("parent", "A new parent, or none to clear it.").orNone,
      blockedBy,
      placement,
      comment,
      Opts.option[Int]("expected-version", "Refuse the write if the issue has moved past this version.").orNone,
      json
    ).mapN((id, title, body, labelled, parent, blockers, place, note, version, asJson) =>
      checked(Issues.edit(_, id, EditFlags(title, body, labelled, parent, blockers, place, note, version), asJson))
    )

  private val claim: Opts[Action] =
    (issue, as, comment, json).mapN((id, who, note, asJson) => checked(Issues.claim(_, id, who, note, asJson)))

  private val release: Opts[Action] =
    (issue, as, comment, json).mapN((id, who, note, asJson) => checked(Issues.release(_, id, who, note, asJson)))

  private val reassign: Opts[Action] =
    (
      issue,
      Opts.option[String]("from", "Who you expect holds it now, or none."),
      Opts.option[String]("to", "Who should hold it, or none to clear a stale claim."),
      comment,
      json
    ).mapN((id, from, to, note, asJson) => checked(Issues.reassign(_, id, from, to, note, asJson)))

  private val close: Opts[Action] =
    (
      Opts.option[String]("query", "Close every issue this query matches, children first.").orNone,
      Opts.arguments[String]("ID RESOLUTION"),
      why,
      json
    ).mapN: (query, positional, note, asJson) =>
      (query, positional.toList) match
        case (Some(matching), List(resolution)) => checked(Issues.closeMatching(_, matching, resolution, note, asJson))
        case (None, List(id, resolution))       => checked(Issues.close(_, id, resolution, note, asJson))
        case _                                  =>
          context =>
            Output.usage(
              context.environment,
              "usage: tikka close ID done|dropped -m \"why\", or tikka close --query QUERY done|dropped -m \"why\""
            )

  private val reopen: Opts[Action] =
    (issue, why, json).mapN((id, note, asJson) => checked(Issues.reopen(_, id, note, asJson)))

  private val project: Opts[Action] =
    Opts.subcommand("new", "Create a project.")(
      (Opts.argument[String]("KEY"), Opts.argument[String]("NAME"), json)
        .mapN((key, name, asJson) => Projects.create(_, key, name, asJson))
    ) orElse Opts.subcommand("list", "List projects.")(json.map(asJson => Projects.list(_, asJson)))

  private val init: Opts[Action] =
    (
      Opts.argument[String]("KEY"),
      Opts.option[String]("new", "Create the project with this name first.").orNone,
      json
    ).mapN((key, name, asJson) => Projects.init(_, key, name, asJson))

  private val daemon: Opts[Action] =
    List[Opts[Action]](
      Opts.subcommand("run", "Run the daemon in the foreground.")(Opts(DaemonControl.run)),
      Opts.subcommand("install", "Install the daemon as a service that starts at login.")(Opts(DaemonControl.install)),
      Opts.subcommand("start", "Start the installed service.")(Opts(DaemonControl.start)),
      Opts.subcommand("stop", "Stop the installed service.")(Opts(DaemonControl.stop)),
      Opts.subcommand("restart", "Restart the installed service.")(Opts(DaemonControl.restart)),
      Opts.subcommand("status", "Say whether the daemon is running.")(Opts(DaemonControl.status)),
      Opts.subcommand("logs", "Print the end of the daemon's log.")(
        Opts.option[Int]("lines", "How many lines.").withDefault(50).map(lines => DaemonControl.logs(_, lines))
      ),
      Opts.subcommand("export", "Write the store as JSON Lines; works with no daemon running.")(
        Opts.argument[String]("FILE").map(file => DaemonControl.exportTo(_, file))
      ),
      Opts.subcommand("restore", "Put a snapshot back, with the daemon stopped.")(
        Opts.argument[String]("SNAPSHOT").map(snapshot => DaemonControl.restore(_, snapshot))
      )
    ).reduce(_ orElse _)

  private def command(write: String => IO[Unit]): Command[Action] =
    val version = Opts
      .flag("version", "Print the version.")
      .as[Action](context => context.environment.out(s"tikka ${BuildVersion.current.value}").as(ExitCode.Success))
    val commands = List[Opts[Action]](
      Opts.subcommand("search", "Find issues with a query, such as `ready label:bug`.")(search),
      Opts.subcommand("ls", "Same as search.")(search),
      Opts.subcommand("show", "Show one issue.")(show),
      Opts.subcommand("new", "Create an issue.")(create),
      Opts.subcommand("edit", "Change an issue; with no flags, edit its body in $EDITOR.")(edit),
      Opts.subcommand("claim", "Take an issue before working on it.")(claim),
      Opts.subcommand("release", "Give back an issue you hold.")(release),
      Opts.subcommand("reassign", "Move an issue someone else holds.")(reassign),
      Opts.subcommand("close", "Close an issue as done or dropped.")(close),
      Opts.subcommand("reopen", "Reopen a closed issue.")(reopen),
      Opts.subcommand("project", "Create and list projects.")(project),
      Opts.subcommand("init", "Bind this directory to a project, for the CLI and MCP clients.")(init),
      Opts.subcommand("mcp", "Serve MCP over stdio for clients that only spawn servers.")(
        Opts(context => McpProxy.run(context, write))
      ),
      Opts.subcommand("daemon", "Run and manage the tikka daemon.")(daemon)
    )
    Command("tikka", "Agent-first, local-only work tracking.")(commands.foldLeft(version)(_ orElse _))
