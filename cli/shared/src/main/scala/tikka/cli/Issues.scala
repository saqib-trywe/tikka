package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import cats.syntax.all.*
import tikka.shared.Render
import tikka.shared.Tri
import tikka.shared.Wire.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** What a command needs: the world, its settings, the daemon's API and a way to start other programs. The raw backend
  * is there for the MCP proxy, which forwards bytes rather than typed requests.
  */
final case class Context(
    environment: Environment,
    settings: Settings,
    api: Api,
    processes: Processes,
    backend: sttp.client4.Backend[IO]
)

/** Rank placement from `--rank`, `--before` or `--after`; at most one may be given. */
final case class Placement(rank: Option[Double], before: Option[String], after: Option[String])

/** The flags of `tikka edit`. With none of them, the body opens in `$EDITOR` instead. */
final case class EditFlags(
    title: Option[String],
    bodyFile: Option[String],
    labels: List[String],
    parent: Option[String],
    blockedBy: List[String],
    placement: Placement,
    comment: Option[String],
    expectedVersion: Option[Int]
):
  def isEmpty: Boolean =
    title.isEmpty && bodyFile.isEmpty && labels.isEmpty && parent.isEmpty && blockedBy.isEmpty &&
      placement == Placement(None, None, None) && comment.isEmpty && expectedVersion.isEmpty

object Issues:
  def search(context: Context, query: String, cursor: Option[String], limit: Option[Int], json: Boolean): IO[ExitCode] =
    context.api.search(query, cursor, limit).flatMap(Output.handle(context.environment, json)(_)(Render.search))

  def show(context: Context, id: String, events: Boolean, json: Boolean): IO[ExitCode] =
    context.api.get(id, events).flatMap(Output.handle(context.environment, json)(_)(Render.issue))

  def create(
      context: Context,
      title: String,
      project: Option[String],
      bodyFile: Option[String],
      labels: List[String],
      parent: Option[String],
      blockedBy: List[String],
      placement: Placement,
      comment: Option[String],
      json: Boolean
  ): IO[ExitCode] =
    readBody(context, bodyFile).flatMap: body =>
      val request = CreateIn(
        title,
        project,
        body,
        Option.when(labels.nonEmpty)(labels),
        parent,
        Option.when(blockedBy.nonEmpty)(blockedBy),
        placement.rank,
        placement.before,
        placement.after,
        comment
      )
      context.api.create(request).flatMap(Output.handle(context.environment, json)(_)(Render.written))

  def edit(context: Context, id: String, flags: EditFlags, json: Boolean): IO[ExitCode] =
    if flags.isEmpty then Editor.edit(context.environment, context.settings, context.api, context.processes, id, json)
    else
      readBody(context, flags.bodyFile).flatMap: body =>
        val (addLabels, removeLabels) = signed(flags.labels)
        val (addBlockers, dropBlockers) = signed(flags.blockedBy)
        val request = UpdateIn(
          expectedVersion = flags.expectedVersion,
          title = flags.title,
          body = body,
          labelsAdd = Option.when(addLabels.nonEmpty)(addLabels),
          labelsRemove = Option.when(removeLabels.nonEmpty)(removeLabels),
          parent = flags.parent.fold(Tri.Missing)(value => if value == "none" then Tri.Cleared else Tri.Set(value)),
          blockedByAdd = Option.when(addBlockers.nonEmpty)(addBlockers),
          blockedByRemove = Option.when(dropBlockers.nonEmpty)(dropBlockers),
          rank = flags.placement.rank,
          rankBefore = flags.placement.before,
          rankAfter = flags.placement.after,
          comment = flags.comment
        )
        context.api.update(id, request).flatMap(Output.handle(context.environment, json)(_)(Render.written))

  def claim(context: Context, id: String, as: Option[String], comment: Option[String], json: Boolean): IO[ExitCode] =
    val who = Settings.assignee(context.environment, as)
    context.api.claim(id, ClaimIn(who, comment)).flatMap(Output.handle(context.environment, json)(_)(Render.claimed))

  def release(context: Context, id: String, as: Option[String], comment: Option[String], json: Boolean): IO[ExitCode] =
    val who = Settings.assignee(context.environment, as)
    context.api
      .release(id, ClaimIn(who, comment))
      .flatMap(Output.handle(context.environment, json)(_)(out => Render.row(out.row)))

  def reassign(
      context: Context,
      id: String,
      from: String,
      to: String,
      comment: Option[String],
      json: Boolean
  ): IO[ExitCode] =
    context.api
      .reassign(id, ReassignIn(from, to, comment))
      .flatMap(Output.handle(context.environment, json)(_)(out => Render.row(out.row)))

  def close(context: Context, id: String, resolution: String, comment: String, json: Boolean): IO[ExitCode] =
    context.api
      .close(id, CloseIn(resolution, comment))
      .flatMap(Output.handle(context.environment, json)(_)(Render.closed))

  def reopen(context: Context, id: String, comment: String, json: Boolean): IO[ExitCode] =
    context.api.reopen(id, ReopenIn(comment)).flatMap(Output.handle(context.environment, json)(_)(Render.written))

  /** Bulk close: one ordinary close per issue, children before their parents, stopping at the first rejection. It is
    * not atomic, by design: one core call never mutates many issues, so this lists exactly what it closed.
    */
  def closeMatching(context: Context, query: String, resolution: String, comment: String, json: Boolean): IO[ExitCode] =
    everything(context.api, query, None, Nil).flatMap:
      case Left(failure) => Output.failure(context.environment, json, failure)
      case Right(rows)   =>
        closeInOrder(context, leavesFirst(rows), resolution, comment, json, Nil)

  private def closeInOrder(
      context: Context,
      remaining: List[RowOut],
      resolution: String,
      comment: String,
      json: Boolean,
      closed: List[RowOut]
  ): IO[ExitCode] =
    remaining match
      case Nil =>
        context.environment.out(summary(closed.reverse)).as(ExitCode.Success)
      case next :: rest =>
        context.api
          .close(next.id, CloseIn(resolution, comment))
          .flatMap:
            case Right(done)   => closeInOrder(context, rest, resolution, comment, json, done.row :: closed)
            case Left(failure) =>
              context.environment.out(summary(closed.reverse)) *>
                context.environment.err(s"stopped at ${next.id}; ${rest.size} more left open") *>
                Output.failure(context.environment, json, failure)

  private def summary(closed: List[RowOut]): String =
    (s"closed ${closed.size}" :: closed.map(Render.row)).mkString("\n")

  /** Children come before their parents, because a closed issue has no open children. Within a depth, the query's own
    * order is kept.
    */
  def leavesFirst(rows: List[RowOut]): List[RowOut] =
    val byId = rows.map(row => row.id -> row).toMap
    def depth(row: RowOut, seen: Set[String]): Int =
      row.parent.flatMap(byId.get).filterNot(parent => seen.contains(parent.id)) match
        case None         => 0
        case Some(parent) => 1 + depth(parent, seen + row.id)
    rows.zipWithIndex.sortBy((row, index) => (-depth(row, Set.empty), index)).map(_._1)

  private def everything(
      api: Api,
      query: String,
      cursor: Option[String],
      gathered: List[RowOut]
  ): IO[Either[Failure, List[RowOut]]] =
    api
      .search(query, cursor, Some(200))
      .flatMap:
        case Left(failure)               => IO.pure(Left(failure))
        case Right(page) if page.hasMore => everything(api, query, page.nextCursor, gathered ++ page.issues)
        case Right(page)                 => IO.pure(Right(gathered ++ page.issues))

  /** `--label +a -b` style values: a leading `-` removes, a leading `+` or nothing adds. */
  private def signed(values: List[String]): (List[String], List[String]) =
    values.partitionMap: value =>
      if value.startsWith("-") then Right(value.drop(1))
      else Left(value.stripPrefix("+"))

  /** `--body-file -` reads the body from standard input. */
  private def readBody(context: Context, bodyFile: Option[String]): IO[Option[String]] =
    bodyFile.traverse:
      case "-"  => context.environment.input.compile.toList.map(_.mkString("\n"))
      case path =>
        IO.blocking(String(Files.readAllBytes(context.environment.workingDirectory.resolve(path)), UTF_8))
