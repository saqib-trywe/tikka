package tikka.daemon

import cats.data.EitherT
import cats.effect.IO
import tikka.core.Core
import tikka.shared.*
import tikka.shared.Wire.*

/** The nine operations both adapters offer, each written once.
  *
  * HTTP and MCP differ in how a request arrives — a path segment against a JSON field, a header against a URL parameter
  * — and in nothing after it. Everything between the arguments and the answer lives here, so a tool and its route
  * cannot drift into refusing different things, which is the claim behind "thin adapters over one core".
  *
  * The reads and writes that only one surface offers — the event feed, project creation — stay with that surface.
  */
object Operations:
  type Result[A] = EitherT[IO, DomainError, A]

  def search(
      core: Core,
      query: String,
      binding: Option[ProjectKey],
      cursor: Option[String],
      limit: Int
  ): Result[SearchOut] =
    call(core.search(query, binding, cursor, limit)).map(SearchOut.from)

  def get(core: Core, id: String, events: Boolean): Result[IssueOut] =
    for
      issue <- lift(Requests.issueId(id))
      view <- call(core.get(issue, events))
    yield IssueOut.from(view)

  def create(core: Core, in: CreateIn, binding: Option[ProjectKey], actor: Actor): Result[WrittenOut] =
    for
      command <- lift(Requests.create(in))
      written <- call(core.create(command, binding, actor))
    yield WrittenOut.from(written)

  def update(core: Core, id: String, in: UpdateIn, actor: Actor): Result[WrittenOut] =
    for
      issue <- lift(Requests.issueId(id))
      command <- lift(Requests.update(in))
      written <- call(core.update(issue, command, actor))
    yield WrittenOut.from(written)

  def claim(core: Core, id: String, in: ClaimIn, actor: Actor): Result[ClaimedOut] =
    for
      issue <- lift(Requests.issueId(id))
      who <- lift(Requests.assignee("assignee", in.assignee))
      claimed <- call(core.claim(issue, who, in.comment, actor))
    yield ClaimedOut.from(claimed)

  def release(core: Core, id: String, in: ClaimIn, actor: Actor): Result[RowOnly] =
    for
      issue <- lift(Requests.issueId(id))
      who <- lift(Requests.assignee("assignee", in.assignee))
      row <- call(core.release(issue, who, in.comment, actor))
    yield RowOnly(RowOut.from(row))

  def reassign(core: Core, id: String, in: ReassignIn, actor: Actor): Result[RowOnly] =
    for
      issue <- lift(Requests.issueId(id))
      previous <- lift(Requests.holder("from", in.from))
      next <- lift(Requests.holder("to", in.to))
      row <- call(core.reassign(issue, previous, next, in.comment, actor))
    yield RowOnly(RowOut.from(row))

  def close(core: Core, id: String, in: CloseIn, actor: Actor): Result[ClosedOutWire] =
    for
      issue <- lift(Requests.issueId(id))
      resolution <- lift(Requests.resolution(in.resolution))
      comment <- lift(Requests.requiredComment(in.comment))
      closed <- call(core.close(issue, resolution, comment, actor))
    yield ClosedOutWire.from(closed)

  def reopen(core: Core, id: String, in: ReopenIn, actor: Actor): Result[WrittenOut] =
    for
      issue <- lift(Requests.issueId(id))
      comment <- lift(Requests.requiredComment(in.comment))
      written <- call(core.reopen(issue, comment, actor))
    yield WrittenOut.from(written)

  def lift[A](value: Either[DomainError, A]): Result[A] = EitherT.fromEither(value)

  def call[A](program: IO[Either[DomainError, A]]): Result[A] = EitherT(program)
