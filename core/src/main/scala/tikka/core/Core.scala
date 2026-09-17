package tikka.core

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import tikka.prose.Mentions
import tikka.shared.*
// doobie also has a Query; the explicit import says which one this file means.
import tikka.shared.Query

/** The rules, in one place, so every surface inherits the same ones.
  *
  * Invariants are checked as **state** invariants: any write that would leave the data violating one is rejected,
  * whatever kind of write it is. The checks run inside the write transaction, where nothing else can interleave.
  *
  * `changed` runs after every committed write, so live views learn there is something new without polling.
  */
final class Core(store: Store, clock: Clock, changed: IO[Unit] = IO.unit):
  private type Tx[A] = EitherT[ConnectionIO, DomainError, A]

  private val timelineLimit = 50

  // Projects

  def projects: IO[List[Project]] = store.reading(Queries.projects)

  /** Creating a project rescans existing prose for its key, so whether a token counts never depends on when the text
    * was written.
    */
  def createProject(key: ProjectKey, name: String): IO[Either[DomainError, Project]] =
    write: at =>
      for
        existing <- ok(Queries.project(key))
        _ <- reject(existing.isDefined, DomainError.InvalidArgument("key", s"project ${key.value} already exists"))
        _ <- ok(Queries.insertProject(key, name, at))
        keys <- ok(Queries.projectKeys)
        _ <- ok(rescanMentions(keys))
      yield Project(key, name, at)

  // Reading

  def get(id: IssueId, includeEvents: Boolean): IO[Either[DomainError, IssueView]] =
    store.reading:
      (for
        record <- load(id)
        detail <- ok(detailOf(record))
        events <- ok(if includeEvents then timelineOf(record.key).map(Some(_)) else none[EventPage].pure[ConnectionIO])
      yield IssueView(detail, events)).value

  /** The whole log, oldest first. */
  def allEvents: IO[List[Event]] = store.reading(Events.all)

  /** Every event after a sequence number, across all issues, oldest first: what a live view resumes from. */
  def feed(after: Long, limit: Int): IO[Either[DomainError, EventSlice]] =
    withinLimit(limit).traverse: _ =>
      store.reading(Events.after(after, limit + 1)).map(slice(limit))

  /** One issue's timeline after a sequence number, with full before-and-after text. */
  def history(id: IssueId, after: Long, limit: Int): IO[Either[DomainError, EventSlice]] =
    withinLimit(limit) match
      case Left(error) => IO.pure(Left(error))
      case Right(_)    =>
        store.reading:
          (for
            record <- load(id)
            events <- ok(Events.timelineAfter(record.key, after, limit + 1))
          yield slice(limit)(events)).value

  private def slice(limit: Int)(events: List[Event]): EventSlice =
    EventSlice(events.take(limit), events.size > limit)

  private def withinLimit(limit: Int): Either[DomainError, Unit] =
    Either.cond(
      limit >= 1 && limit <= Core.maxLimit,
      (),
      DomainError.InvalidArgument("limit", s"a page holds 1 to ${Core.maxLimit} items")
    )

  /** Runs a query written in the shared grammar. */
  def search(
      text: String,
      binding: Option[ProjectKey],
      cursor: Option[String],
      limit: Int
  ): IO[Either[DomainError, SearchPage]] =
    QueryParser.parse(text) match
      case Left(error)  => IO.pure(Left(DomainError.InvalidQuery(error.token, error.reason, error.suggestions)))
      case Right(query) => search(query, binding, cursor, limit)

  /** An unscoped query stays inside the bound project; an explicit `project:` always wins. */
  def search(
      query: Query,
      binding: Option[ProjectKey],
      cursor: Option[String],
      limit: Int
  ): IO[Either[DomainError, SearchPage]] =
    val effective = scoped(query, binding)
    clock.instant.flatMap: at =>
      val prepared =
        for
          _ <- Either.cond(
            limit >= 1 && limit <= Core.maxLimit,
            (),
            DomainError.InvalidArgument("limit", s"a page holds 1 to ${Core.maxLimit} issues")
          )
          keyset <- cursor.traverse(text => Cursor.decode(text, effective.sort))
          where <- Search.conditions(effective, at, clock.zone)
        yield (keyset, where)
      prepared match
        case Left(error)            => IO.pure(Left(error))
        case Right((keyset, where)) => store.reading(page(effective, where, keyset, limit)).map(Right(_))

  private def scoped(query: Query, binding: Option[ProjectKey]): Query =
    binding match
      case Some(key) if query.withoutProjectScope =>
        query.copy(terms = Term(negated = false, Filter.Project(ProjectScope.Keys(List(key)))) +: query.terms)
      case _ => query

  private def page(query: Query, where: Fragment, keyset: Option[Search.Keyset], limit: Int): ConnectionIO[SearchPage] =
    val after = keyset.fold(fr"1 = 1")(Search.after(query.sort, _))
    // One row beyond the page says whether there is more, without a second count query.
    val statement = fr"SELECT" ++ Queries.issueColumns ++ fr"FROM issue WHERE" ++ where ++ fr"AND" ++ after ++
      Search.order(query.sort) ++ fr"LIMIT ${limit + 1}"
    for
      records <- statement.query[IssueRecord].to[List]
      visible = records.take(limit)
      rows <- visible.traverse(rowOf)
    yield
      val more = records.size > limit
      val next = visible
        .zip(rows)
        .lastOption
        .map((record, row) => Cursor.encode(query.sort, keysetFor(query.sort, record, row)))
      SearchPage(query.render, rows, next.filter(_ => more), more)

  private def keysetFor(sort: Sort, record: IssueRecord, row: Row): Search.Keyset =
    val project = record.projectKey.value
    val number = record.number.value
    sort.field match
      case SortField.Rank    => Search.Keyset(missing = false, record.rank.value.toString, project, number)
      case SortField.Created => Search.Keyset(missing = false, record.created.value, project, number)
      case SortField.Updated => Search.Keyset(missing = false, row.updated.value, project, number)
      case SortField.Closed  =>
        record.closedAt.fold(Search.Keyset(missing = true, "", project, number))(at =>
          Search.Keyset(missing = false, at.value, project, number)
        )

  // Writing

  def create(command: CreateIssue, binding: Option[ProjectKey], actor: Actor): IO[Either[DomainError, Written]] =
    write(at => createTx(command, binding, actor, at))

  def update(id: IssueId, command: UpdateIssue, actor: Actor): IO[Either[DomainError, Written]] =
    write(at => updateTx(id, command, actor, at))

  def claim(id: IssueId, assignee: Assignee, comment: Option[String], actor: Actor): IO[Either[DomainError, Claimed]] =
    write(at => claimTx(id, assignee, comment, actor, at))

  def release(id: IssueId, assignee: Assignee, comment: Option[String], actor: Actor): IO[Either[DomainError, Row]] =
    write(at => releaseTx(id, assignee, comment, actor, at))

  def reassign(
      id: IssueId,
      from: Option[Assignee],
      to: Option[Assignee],
      comment: Option[String],
      actor: Actor
  ): IO[Either[DomainError, Row]] =
    write(at => reassignTx(id, from, to, comment, actor, at))

  def close(
      id: IssueId,
      resolution: Resolution,
      comment: String,
      actor: Actor
  ): IO[Either[DomainError, ClosedOut]] =
    write(at => closeTx(id, resolution, comment, actor, at))

  def reopen(id: IssueId, comment: String, actor: Actor): IO[Either[DomainError, Written]] =
    write(at => reopenTx(id, comment, actor, at))

  // Create

  private def createTx(command: CreateIssue, binding: Option[ProjectKey], actor: Actor, at: Timestamp): Tx[Written] =
    for
      keys <- ok(Queries.projectKeys)
      project <- resolveProject(command.project.orElse(binding), keys)
      parent <- command.parent.traverse(target => loadForCreate(target, project, "parent"))
      _ <- parent.traverse_ : record =>
        reject(
          !record.isOpen,
          DomainError.InvalidArgument("parent", s"${record.id.render} is closed; a closed issue has no open children")
        )
      blockers <- command.blockedBy.distinct.traverse(target => loadForCreate(target, project, "blocked_by"))
      number <- ok(Queries.takeNumber(project))
      id = IssueId(project, number)
      body = command.body.getOrElse(Body.empty)
      labels = command.labels.distinct
      ranking <- resolveRank(command.rank, project, number)
      key <- ok(Queries.insertIssue(id, command.title, body, ranking, parent.map(_.key), at))
      _ <- ok(labels.traverse_(label => Queries.addLabel(key, label).void))
      _ <- ok(blockers.traverse_(blocker => Queries.addBlockEdge(blocker.key, key).void))
      _ <- ok(syncProse(key, id, command.title, body, keys))
      _ <- ok(command.comment.traverse_(text => syncCommentMentions(key, id, text, keys)))
      changes = createChanges(command, body, labels, parent, blockers, ranking)
      related = parent.map(_.key).toSet ++ blockers.map(_.key).toSet
      _ <- ok(Events.record(key, actor, at, EventDraft(changes, related, command.comment)))
      // Indexed after the event: comments ride on events, so the index needs the event to exist first.
      _ <- ok(reindex(key, command.title, body))
      row <- ok(rowByKey(key))
    yield Written(row, Version.first)

  private def createChanges(
      command: CreateIssue,
      body: Body,
      labels: List[Label],
      parent: Option[IssueRecord],
      blockers: List[IssueRecord],
      ranking: Rank
  ): List[Change] =
    // A create is the first write, not a special kind of event: `set` for each initial field, `add` for each blocker.
    List(EventDraft.set(ChangeField.Title, None, Some(command.title.value))) ++
      Option.when(body.value.nonEmpty)(EventDraft.set(ChangeField.Body, None, Some(body.value))) ++
      labels.map(label => EventDraft.add(ChangeField.Label, label.value)) ++
      parent.map(record => EventDraft.set(ChangeField.Parent, None, Some(record.id.render))) ++
      blockers.map(record => EventDraft.add(ChangeField.BlockedBy, record.id.render)) ++
      command.rank.map(_ => EventDraft.set(ChangeField.Rank, None, Some(ranking.value.toString)))

  // Update

  private def updateTx(id: IssueId, command: UpdateIssue, actor: Actor, at: Timestamp): Tx[Written] =
    for
      record <- load(id)
      keys <- ok(Queries.projectKeys)
      _ <- guardVersion(record, command)
      title = command.title.filter(_.value != record.title.value)
      newBody <- resolveBody(record, command.body)
      _ <- ok(title.traverse_(Queries.setTitle(record.key, _)))
      _ <- ok(newBody.traverse_(Queries.setBody(record.key, _)))
      labelChanges <- ok(applyLabels(record, command))
      parentResult <- applyParent(record, command.parent)
      blockResult <- applyBlockers(record, command)
      rankChanges <- applyRank(record, command.rank)
      proseChanged = title.isDefined || newBody.isDefined
      touched = proseChanged || labelChanges.nonEmpty
      version <- ok(if touched then Queries.bumpVersion(record.key) else record.version.pure[ConnectionIO])
      currentTitle = title.getOrElse(record.title)
      currentBody = newBody.getOrElse(record.body)
      _ <- ok(if proseChanged then syncProse(record.key, id, currentTitle, currentBody, keys) else unit)
      _ <- ok(command.comment.traverse_(text => syncCommentMentions(record.key, id, text, keys)))
      changes = title.map(value => EventDraft.set(ChangeField.Title, Some(record.title.value), Some(value.value))) ++
        newBody.map(value => EventDraft.set(ChangeField.Body, Some(record.body.value), Some(value.value))) ++
        labelChanges ++ parentResult._1 ++ blockResult._1 ++ rankChanges
      _ <- ok(
        Events.record(
          record.key,
          actor,
          at,
          EventDraft(changes.toList, parentResult._2 ++ blockResult._2, command.comment)
        )
      )
      // Indexed after the event: comments ride on events, so the index needs the event to exist first.
      _ <- ok(
        if proseChanged || command.comment.isDefined then reindex(record.key, currentTitle, currentBody) else unit
      )
      row <- ok(rowByKey(record.key))
    yield Written(row, version)

  /** An optional guard: stated and stale, the write is refused; omitted, the write wins. */
  private def guardVersion(record: IssueRecord, command: UpdateIssue): Tx[Unit] =
    command.expectedVersion match
      case Some(expected) if expected.value != record.version.value =>
        EitherT.leftT(
          DomainError.StaleVersion(
            record.version,
            Option.when(command.title.isDefined)(record.title),
            Option.when(command.body.isDefined)(record.body),
            None
          )
        )
      case _ => EitherT.pure(())

  private def resolveBody(record: IssueRecord, change: Option[BodyChange]): Tx[Option[Body]] = change match
    case None                           => EitherT.pure(None)
    case Some(BodyChange.Replace(body)) =>
      EitherT.pure(Option.when(body.value != record.body.value)(body))
    case Some(BodyChange.Edits(edits)) =>
      EitherT.fromEither(applyEdits(record.body, edits).map(body => Option.when(body.value != record.body.value)(body)))

  /** Exact, unique-match replacements applied in order, all or nothing. */
  private def applyEdits(body: Body, edits: List[BodyEdit]): Either[DomainError, Body] =
    edits
      .foldLeft(Right(body.value): Either[DomainError, String]): (current, edit) =>
        current.flatMap: text =>
          val first = text.indexOf(edit.old)
          if first < 0 then Left(DomainError.EditMismatch(edit, EditMismatchKind.Missing))
          else if text.indexOf(edit.old, first + 1) >= 0 then
            Left(DomainError.EditMismatch(edit, EditMismatchKind.Ambiguous))
          else Right(text.substring(0, first) + edit.replacement + text.substring(first + edit.old.length))
      .map(Body.apply)

  private def applyLabels(record: IssueRecord, command: UpdateIssue): ConnectionIO[List[Change]] =
    for
      existing <- Queries.labels(record.key)
      added = command.labelsAdd.distinct.filterNot(existing.contains)
      removed = command.labelsRemove.distinct.filter(existing.contains)
      _ <- added.traverse_(label => Queries.addLabel(record.key, label).void)
      _ <- removed.traverse_(label => Queries.removeLabel(record.key, label).void)
    yield added.map(label => EventDraft.add(ChangeField.Label, label.value)) ++
      removed.map(label => EventDraft.remove(ChangeField.Label, label.value))

  private def applyParent(record: IssueRecord, change: Option[ParentChange]): Tx[(List[Change], Set[Long])] =
    change match
      case None                     => EitherT.pure((Nil, Set.empty))
      case Some(ParentChange.Clear) =>
        record.parentKey match
          case None           => EitherT.pure((Nil, Set.empty))
          case Some(previous) =>
            for
              old <- ok(Queries.issueByKey(previous))
              _ <- ok(Queries.setParent(record.key, None))
            yield (List(EventDraft.set(ChangeField.Parent, old.map(_.id.render), None)), Set(previous))
      case Some(ParentChange.SetTo(target)) =>
        for
          parent <- loadEdgeTarget(record, target)
          _ <- reject(parent.key == record.key, DomainError.Cycle(List(record.id, record.id)))
          cycle <- ok(Graph.hierarchyPath(record.key, parent.key))
          _ <- cycle.traverse_(path => cyclePath(path, record))
          _ <- reject(
            !parent.isOpen && record.isOpen,
            DomainError.OpenChildren(List(IssueRef(record.id, record.title, record.state)))
          )
          previous <- ok(record.parentKey.flatTraverse(Queries.issueByKey))
          changed = !record.parentKey.contains(parent.key)
          _ <- ok(if changed then Queries.setParent(record.key, Some(parent.key)) else unit)
        yield
          if !changed then (Nil, Set.empty)
          else
            (
              List(EventDraft.set(ChangeField.Parent, previous.map(_.id.render), Some(parent.id.render))),
              previous.map(_.key).toSet + parent.key
            )

  private def applyBlockers(record: IssueRecord, command: UpdateIssue): Tx[(List[Change], Set[Long])] =
    for
      existing <- ok(Queries.blockerKeys(record.key))
      added <- command.blockedByAdd.distinct.traverse(target => loadEdgeTarget(record, target))
      removed <- command.blockedByRemove.distinct.traverse(target => loadEdgeTarget(record, target))
      fresh = added.filterNot(blocker => existing.contains(blocker.key))
      gone = removed.filter(blocker => existing.contains(blocker.key))
      _ <- fresh.traverse_ : blocker =>
        for
          _ <- reject(blocker.key == record.key, DomainError.Cycle(List(record.id, record.id)))
          cycle <- ok(Graph.blockingPath(record.key, blocker.key))
          _ <- cycle.traverse_(path => cyclePath(path, record))
          _ <- reject(
            record.resolution.contains(Resolution.Done) && blocker.isOpen,
            DomainError.OpenBlockers(List(IssueRef(blocker.id, blocker.title, blocker.state)))
          )
          _ <- ok(Queries.addBlockEdge(blocker.key, record.key).void)
        yield ()
      _ <- ok(gone.traverse_(blocker => Queries.removeBlockEdge(blocker.key, record.key).void))
    yield (
      fresh.map(blocker => EventDraft.add(ChangeField.BlockedBy, blocker.id.render)) ++
        gone.map(blocker => EventDraft.remove(ChangeField.BlockedBy, blocker.id.render)),
      (fresh ++ gone).map(_.key).toSet
    )

  private def applyRank(record: IssueRecord, placement: Option[RankPlacement]): Tx[List[Change]] =
    placement match
      case None        => EitherT.pure(Nil)
      case Some(value) =>
        for
          ranking <- placeRank(value, record.projectKey)
          changed = ranking.value != record.rank.value
          _ <- ok(if changed then Queries.setRank(record.key, ranking) else unit)
        yield
          if changed then
            List(EventDraft.set(ChangeField.Rank, Some(record.rank.value.toString), Some(ranking.value.toString)))
          else Nil

  // Claims

  private def claimTx(
      id: IssueId,
      assignee: Assignee,
      comment: Option[String],
      actor: Actor,
      at: Timestamp
  ): Tx[Claimed] =
    for
      record <- load(id)
      _ <- requireOpen(record)
      taken <- ok(Queries.claimIfUnassigned(record.key, assignee, at))
      held <- ok(Queries.issueByKey(record.key))
      claimed <- (taken, held.flatMap(_.assignee)) match
        case (1, _) =>
          ok(
            Events.record(
              record.key,
              actor,
              at,
              EventDraft(List(EventDraft.set(ChangeField.Assignee, None, Some(assignee.value))), Set.empty, comment)
            )
          ).as(at)
        // Claiming what you already hold is not a conflict: it succeeds and changes nothing.
        case (_, Some(holder)) if holder.value == assignee.value =>
          ok(recordComment(record.key, actor, at, comment)).as(held.flatMap(_.claimedAt).getOrElse(at))
        case (_, Some(holder)) =>
          EitherT.leftT[ConnectionIO, Timestamp](DomainError.ClaimConflict(holder, held.flatMap(_.claimedAt)))
        case (_, None) =>
          EitherT.leftT[ConnectionIO, Timestamp](
            DomainError.InvalidArgument("id", s"${id.render} could not be claimed")
          )
      row <- ok(rowByKey(record.key))
    yield Claimed(row, claimed)

  private def releaseTx(
      id: IssueId,
      assignee: Assignee,
      comment: Option[String],
      actor: Actor,
      at: Timestamp
  ): Tx[Row] =
    for
      record <- load(id)
      _ <- requireOpen(record)
      _ <- record.assignee match
        // Releasing an issue nobody holds is a no-op, so a retried release still succeeds.
        case None                                           => ok(recordComment(record.key, actor, at, comment))
        case Some(holder) if holder.value == assignee.value =>
          ok(Queries.setAssignee(record.key, None, None)) *>
            ok(
              Events.record(
                record.key,
                actor,
                at,
                EventDraft(List(EventDraft.set(ChangeField.Assignee, Some(holder.value), None)), Set.empty, comment)
              )
            )
        case Some(holder) => EitherT.leftT[ConnectionIO, Unit](DomainError.NotHolder(Some(holder)))
      row <- ok(rowByKey(record.key))
    yield row

  private def reassignTx(
      id: IssueId,
      from: Option[Assignee],
      to: Option[Assignee],
      comment: Option[String],
      actor: Actor,
      at: Timestamp
  ): Tx[Row] =
    for
      record <- load(id)
      _ <- requireOpen(record)
      current = record.assignee
      _ <- reject(current.map(_.value) != from.map(_.value), DomainError.NotHolder(current))
      _ <-
        if current.map(_.value) == to.map(_.value) then ok(recordComment(record.key, actor, at, comment))
        else
          ok(Queries.setAssignee(record.key, to, to.map(_ => at))) *>
            ok(
              Events.record(
                record.key,
                actor,
                at,
                EventDraft(
                  List(EventDraft.set(ChangeField.Assignee, current.map(_.value), to.map(_.value))),
                  Set.empty,
                  comment
                )
              )
            )
      row <- ok(rowByKey(record.key))
    yield row

  // Closing and reopening

  private def closeTx(
      id: IssueId,
      resolution: Resolution,
      comment: String,
      actor: Actor,
      at: Timestamp
  ): Tx[ClosedOut] =
    for
      record <- load(id)
      result <- record.state match
        // Closing an already-closed issue the same way succeeds, with nothing newly unblocked.
        case IssueState.Closed(existing, _) if existing == resolution =>
          ok(recordComment(record.key, actor, at, Some(comment))) *> ok(rowByKey(record.key)).map(ClosedOut(_, Nil))
        case IssueState.Closed(existing, _) =>
          EitherT.leftT[ConnectionIO, ClosedOut](DomainError.ResolutionImmutable(existing))
        case IssueState.Open => closeOpen(record, resolution, comment, actor, at)
    yield result

  private def closeOpen(
      record: IssueRecord,
      resolution: Resolution,
      comment: String,
      actor: Actor,
      at: Timestamp
  ): Tx[ClosedOut] =
    for
      children <- ok(Queries.openChildren(record.key))
      _ <- reject(children.nonEmpty, DomainError.OpenChildren(children))
      blockers <- ok(Queries.openBlockers(record.key))
      // `done` with an open blocker would claim the work finished before its prerequisites. `dropped` is exempt.
      _ <- reject(resolution == Resolution.Done && blockers.nonEmpty, DomainError.OpenBlockers(blockers))
      dependents <- ok(Queries.blockedKeys(record.key))
      _ <- ok(Queries.closeIssue(record.key, resolution, at))
      freed <- ok(dependents.filterA(nowReadyToProceed))
      changes = List(
        EventDraft.set(ChangeField.Status, Some("open"), Some("closed")),
        EventDraft.set(ChangeField.Resolution, None, Some(resolution.value))
      )
      _ <- ok(Events.record(record.key, actor, at, EventDraft(changes, freed.toSet, Some(comment))))
      row <- ok(rowByKey(record.key))
      rows <- ok(freed.traverse(rowByKey))
    yield ClosedOut(row, rows)

  /** Open, and no longer held back by anything: closing a blocker frees these, whatever the resolution. */
  private def nowReadyToProceed(key: Long): ConnectionIO[Boolean] =
    for
      record <- Queries.issueByKey(key)
      blocked <- Queries.isBlocked(key)
    yield record.exists(_.isOpen) && !blocked

  private def reopenTx(id: IssueId, comment: String, actor: Actor, at: Timestamp): Tx[Written] =
    for
      record <- load(id)
      result <- record.state match
        case IssueState.Open =>
          ok(recordComment(record.key, actor, at, Some(comment))) *> ok(rowByKey(record.key))
            .map(Written(_, record.version))
        case IssueState.Closed(resolution, _) => reopenClosed(record, resolution, comment, actor, at)
    yield result

  private def reopenClosed(
      record: IssueRecord,
      resolution: Resolution,
      comment: String,
      actor: Actor,
      at: Timestamp
  ): Tx[Written] =
    for
      parent <- ok(record.parentKey.flatTraverse(Queries.issueByKey))
      _ <- parent.traverse_ : value =>
        reject(!value.isOpen, DomainError.ReopenBlocked(List(IssueRef(value.id, value.title, value.state))))
      dependents <- ok(Queries.blockedKeys(record.key).flatMap(_.traverse(Queries.issueByKey)).map(_.flatten))
      doneDependents = dependents.filter(_.resolution.contains(Resolution.Done))
      _ <- reject(
        doneDependents.nonEmpty,
        DomainError.ReopenBlocked(doneDependents.map(value => IssueRef(value.id, value.title, value.state)))
      )
      _ <- ok(Queries.reopenIssue(record.key))
      // Reopening blocks its open dependents again, so the event lands on their timelines too.
      blockedAgain = dependents.filter(_.isOpen).map(_.key).toSet
      changes = List(
        EventDraft.set(ChangeField.Status, Some("closed"), Some("open")),
        EventDraft.set(ChangeField.Resolution, Some(resolution.value), None)
      )
      _ <- ok(Events.record(record.key, actor, at, EventDraft(changes, blockedAgain, Some(comment))))
      row <- ok(rowByKey(record.key))
    yield Written(row, record.version)

  // Shared pieces

  private def resolveProject(requested: Option[ProjectKey], keys: Set[ProjectKey]): Tx[ProjectKey] =
    requested match
      case None =>
        EitherT.leftT(
          DomainError.InvalidArgument("project", "this connection is not bound to a project, so name one")
        )
      case Some(key) if keys.contains(key) => EitherT.pure(key)
      case Some(key) => EitherT.leftT(DomainError.UnknownProject(key, keys.toList.sortBy(_.value)))

  /** An edge target on a create, where the issue being created has no id to name in an error yet. */
  private def loadForCreate(target: IssueId, project: ProjectKey, argument: String): Tx[IssueRecord] =
    for
      record <- load(target)
      _ <- reject(
        record.projectKey.value != project.value,
        DomainError.InvalidArgument(
          argument,
          s"${target.render} is in ${record.projectKey.value}, and parent and blocking edges stay in one project"
        )
      )
    yield record

  private def loadEdgeTarget(record: IssueRecord, target: IssueId): Tx[IssueRecord] =
    for
      other <- load(target)
      _ <- reject(
        other.projectKey.value != record.projectKey.value,
        DomainError.CrossProjectEdge(record.id, other.id)
      )
    yield other

  /** Every cycle rejection returns the offending path, so the caller can see the loop it would have closed. */
  private def cyclePath(path: List[Long], record: IssueRecord): Tx[Unit] =
    for
      ids <- ok(path.traverse(Queries.issueByKey).map(_.flatten.map(_.id)))
      _ <- EitherT.leftT[ConnectionIO, Unit](DomainError.Cycle(ids :+ record.id))
    yield ()

  private def placeRank(placement: RankPlacement, project: ProjectKey): Tx[Rank] = placement match
    case RankPlacement.At(value)      => EitherT.pure(value)
    case RankPlacement.Before(target) =>
      for
        record <- load(target)
        neighbour <- ok(Queries.rankBelow(project, record.rank))
        ranking <- EitherT.fromEither(
          between(neighbour.map(_.value).getOrElse(record.rank.value - 1.0), record.rank.value)
        )
      yield ranking
    case RankPlacement.After(target) =>
      for
        record <- load(target)
        neighbour <- ok(Queries.rankAbove(project, record.rank))
        ranking <- EitherT.fromEither(
          between(record.rank.value, neighbour.map(_.value).getOrElse(record.rank.value + 1.0))
        )
      yield ranking

  private def resolveRank(placement: Option[RankPlacement], project: ProjectKey, number: IssueNumber): Tx[Rank] =
    placement match
      // Rank defaults to the issue number, so order is deterministic without anyone setting it.
      case None        => EitherT.pure(Rank.trusted(number.value.toDouble))
      case Some(value) => placeRank(value, project)

  /** Halving between the same neighbours runs out of precision after about 50 steps; then the write is refused. */
  private def between(low: Double, high: Double): Either[DomainError, Rank] =
    val midpoint = low + (high - low) / 2
    if midpoint <= low || midpoint >= high then Left(DomainError.RankPrecision(Rank.trusted(low), Rank.trusted(high)))
    else Right(Rank.trusted(midpoint))

  private def requireOpen(record: IssueRecord): Tx[Unit] =
    record.state match
      // A closed issue's assignee is the record of who resolved it, so it cannot be claimed, released or reassigned.
      case IssueState.Closed(resolution, at) => EitherT.leftT(DomainError.IssueClosed(resolution, at))
      case IssueState.Open                   => EitherT.pure(())

  private def recordComment(key: Long, actor: Actor, at: Timestamp, comment: Option[String]): ConnectionIO[Unit] =
    Events.record(key, actor, at, EventDraft(Nil, Set.empty, comment)).void

  private def syncProse(key: Long, id: IssueId, title: Title, body: Body, keys: Set[ProjectKey]): ConnectionIO[Unit] =
    val targets = Mentions.detect(title.value, keys, Some(id)) ++ Mentions.detect(body.value, keys, Some(id))
    Queries.replaceProseMentions(key, targets)

  private def syncCommentMentions(key: Long, id: IssueId, text: String, keys: Set[ProjectKey]): ConnectionIO[Unit] =
    Queries.addCommentMentions(key, Mentions.detect(text, keys, Some(id)))

  private def rescanMentions(keys: Set[ProjectKey]): ConnectionIO[Unit] =
    Queries.issuesWithProse.flatMap:
      _.traverse_ : (key, id, title, body) =>
        for
          _ <- syncProse(key, id, title, body, keys)
          _ <- Queries.clearCommentMentions(key)
          comments <- Queries.commentsOf(key)
          _ <- comments.traverse_(comment => syncCommentMentions(key, id, comment.text, keys))
        yield ()

  private def reindex(key: Long, title: Title, body: Body): ConnectionIO[Unit] =
    Queries
      .commentsOf(key)
      .map(_.map(_.text).mkString("\n"))
      .flatMap(comments => Queries.indexIssue(key, title, body, comments))

  private def rowOf(record: IssueRecord): ConnectionIO[Row] =
    for
      labels <- Queries.labels(record.key)
      parent <- record.parentKey.flatTraverse(Queries.issueByKey)
      blocked <- Queries.isBlocked(record.key)
      updated <- Events.updatedAt(record.key, record.created)
    yield Row(
      record.id,
      record.title,
      record.state,
      record.assignee,
      labels,
      parent.map(_.id),
      blocked,
      record.rank,
      updated
    )

  private def rowByKey(key: Long): ConnectionIO[Row] =
    Queries
      .issueByKey(key)
      .flatMap:
        case Some(record) => rowOf(record)
        case None         => throw IllegalStateException(s"issue row $key vanished mid-transaction")

  private def detailOf(record: IssueRecord): ConnectionIO[IssueDetail] =
    for
      row <- rowOf(record)
      children <- Queries.children(record.key)
      blockers <- Queries.blockers(record.key)
      blocking <- Queries.blocking(record.key)
      mentions <- Queries.mentions(record.key)
      backlinks <- Queries.backlinks(record.id, record.key)
      comments <- Queries.commentsOf(record.key)
    yield IssueDetail(
      row,
      record.body,
      record.version,
      record.created,
      record.claimedAt,
      children,
      blockers,
      blocking,
      mentions,
      backlinks,
      comments
    )

  private def timelineOf(key: Long): ConnectionIO[EventPage] =
    for
      total <- Events.total(key)
      events <- Events.timeline(key, timelineLimit)
    yield EventPage(events, total, total > events.size)

  private def load(id: IssueId): Tx[IssueRecord] =
    EitherT(Queries.issue(id).map(_.toRight(DomainError.NotFound(id))))

  private def ok[A](program: ConnectionIO[A]): Tx[A] = EitherT.liftF(program)

  private def reject(condition: Boolean, error: => DomainError): Tx[Unit] =
    if condition then EitherT.leftT(error) else EitherT.pure(())

  private val unit: ConnectionIO[Unit] = ().pure[ConnectionIO]

  /** A rejection must roll the transaction back: the checks and the writes share one transaction, and a rejection
    * returned as a value would otherwise commit whatever ran before it.
    */
  private def write[A](transaction: Timestamp => Tx[A]): IO[Either[DomainError, A]] =
    clock.now.flatMap: at =>
      store
        .writing:
          transaction(at).value.flatMap:
            case Left(error)  => Rejected(error).raiseError[ConnectionIO, Either[DomainError, A]]
            case Right(value) => Right(value).pure[ConnectionIO]
        .recover:
          case Rejected(error) => Left(error)
        .flatTap(result => changed.whenA(result.isRight))

object Core:
  /** The contract's page-size ceiling. Asking for more is refused rather than quietly clamped. */
  val maxLimit: Int = 200

  val defaultLimit: Int = 50

private final case class Rejected(error: DomainError) extends RuntimeException("write rejected", null, false, false)
