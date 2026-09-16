package tikka.core

import cats.effect.IO
import cats.syntax.all.*
import tikka.shared.*

/** The invariants from "Define tikka's core invariants", against a real store. */
class InvariantsTest extends TempHome, Builders:
  home.test("a closed issue has no open children, and the rejection lists them"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        parent <- core.addTo(project, "The effort")
        child <- core.add(creating("A step", project = Some(project), parent = Some(parent)))
        refused <- core.close(parent, Resolution.Done, "finished", actor)
        dropped <- core.close(parent, Resolution.Dropped, "abandoned", actor)
      yield
        assertEquals(refused.left.map(_.code), Left(ErrorCode.OpenChildren))
        assertEquals(
          refused.left.toOption.collect { case DomainError.OpenChildren(kids) => kids.map(_.id) },
          Some(List(child))
        )
        // Neither resolution may leave an open child behind: dropping a big effort is a bulk job, not a cascade.
        assertEquals(dropped.left.map(_.code), Left(ErrorCode.OpenChildren))

  home.test("an issue closed done has no open blockers, but dropped is exempt"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        blocker <- core.addTo(project, "Prerequisite")
        work <- core.add(creating("The work", project = Some(project), blockedBy = List(blocker)))
        asDone <- core.close(work, Resolution.Done, "finished", actor)
        dropped <- core.close(work, Resolution.Dropped, "not worth it", actor)
      yield
        assertEquals(asDone.left.map(_.code), Left(ErrorCode.OpenBlockers))
        assert(dropped.isRight, dropped)

  home.test("closing a blocker frees its dependents, whatever the resolution, and names them"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        blocker <- core.addTo(project, "Prerequisite")
        first <- core.add(creating("Waiting", project = Some(project), blockedBy = List(blocker)))
        second <- core.add(creating("Also waiting", project = Some(project), blockedBy = List(blocker)))
        before <- core.detail(first)
        closed <- core.close(blocker, Resolution.Dropped, "obsolete", actor)
        after <- core.detail(first)
      yield
        assert(before.row.blocked, "an open blocker blocks")
        assertEquals(closed.map(_.newlyUnblocked.map(_.id)), Right(List(first, second)))
        assert(!after.row.blocked, "a closed blocker does not")

  home.test("hierarchy cannot cycle, and the rejection returns the path"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        grandparent <- core.addTo(project, "Top")
        parent <- core.add(creating("Middle", project = Some(project), parent = Some(grandparent)))
        child <- core.add(creating("Bottom", project = Some(project), parent = Some(parent)))
        refused <- core.reparent(grandparent, child)
        itself <- core.reparent(parent, parent)
      yield
        assertEquals(refused.left.map(_.code), Left(ErrorCode.Cycle))
        assertEquals(
          refused.left.toOption.collect { case DomainError.Cycle(path) => path },
          Some(List(grandparent, parent, child, grandparent))
        )
        assertEquals(itself.left.map(_.code), Left(ErrorCode.Cycle))

  home.test("blocking cannot cycle"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        first <- core.addTo(project, "First")
        second <- core.add(creating("Second", project = Some(project), blockedBy = List(first)))
        refused <- core.blockOn(first, second)
      yield
        assertEquals(refused.left.map(_.code), Left(ErrorCode.Cycle))
        assertEquals(
          refused.left.toOption.collect { case DomainError.Cycle(path) => path },
          Some(List(first, second, first))
        )

  home.test("parent and blocking edges stay within one project"): directory =>
    withCore(directory): core =>
      for
        tik <- core.project("TIK")
        wf2 <- core.project("WF2")
        here <- core.addTo(tik, "Here")
        there <- core.addTo(wf2, "There")
        parent <- core.reparent(here, there)
        blocked <- core.blockOn(here, there)
        created <- core.create(creating("Child", project = Some(tik), parent = Some(there)), None, actor)
      yield
        assertEquals(parent.left.map(_.code), Left(ErrorCode.CrossProjectEdge))
        assertEquals(blocked.left.map(_.code), Left(ErrorCode.CrossProjectEdge))
        assertEquals(created.left.map(_.code), Left(ErrorCode.InvalidArgument))

  home.test("an open issue cannot be parented to a closed one"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        parent <- core.addTo(project, "Done already")
        child <- core.addTo(project, "Still going")
        _ <- core.close(parent, Resolution.Done, "finished", actor)
        refused <- core.reparent(child, parent)
      yield assertEquals(refused.left.map(_.code), Left(ErrorCode.OpenChildren))

  home.test("a closed issue's resolution is immutable, and closing it the same way again succeeds"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "The work")
        _ <- core.close(issue, Resolution.Done, "finished", actor)
        again <- core.close(issue, Resolution.Done, "finished again", actor)
        other <- core.close(issue, Resolution.Dropped, "actually no", actor)
      yield
        assertEquals(again.map(_.newlyUnblocked), Right(Nil))
        assertEquals(other.left.map(_.code), Left(ErrorCode.ResolutionImmutable))

  home.test("reopening is refused while the parent is closed or a dependent is done"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        parent <- core.addTo(project, "Parent")
        child <- core.add(creating("Child", project = Some(project), parent = Some(parent)))
        _ <- core.close(child, Resolution.Done, "done", actor)
        _ <- core.close(parent, Resolution.Done, "done", actor)
        refused <- core.reopen(child, "back on", actor)
        blocker <- core.addTo(project, "Blocker")
        follower <- core.add(creating("Follower", project = Some(project), blockedBy = List(blocker)))
        _ <- core.close(blocker, Resolution.Done, "done", actor)
        _ <- core.close(follower, Resolution.Done, "done", actor)
        refused2 <- core.reopen(blocker, "back on", actor)
      yield
        assertEquals(refused.left.map(_.code), Left(ErrorCode.ReopenBlocked))
        assertEquals(refused2.left.map(_.code), Left(ErrorCode.ReopenBlocked))

  home.test("reopening clears the resolution and keeps the assignee"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "The work")
        _ <- core.claim(issue, holder("saqib/wf-7f3a"), None, actor)
        _ <- core.close(issue, Resolution.Done, "finished", actor)
        _ <- core.reopen(issue, "more to do", actor)
        after <- core.detail(issue)
      yield
        assertEquals(after.row.state, IssueState.Open)
        assertEquals(after.row.assignee.map(_.value), Some("saqib/wf-7f3a"))

  home.test("the assignee moves only by claim, release and reassign, each conditional on who holds it"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "The work")
        claimed <- core.claim(issue, holder("first"), None, actor)
        again <- core.claim(issue, holder("first"), None, actor)
        conflict <- core.claim(issue, holder("second"), None, actor)
        notMine <- core.release(issue, holder("second"), None, actor)
        wrongFrom <- core.reassign(issue, Some(holder("second")), Some(holder("third")), None, actor)
        moved <- core.reassign(issue, Some(holder("first")), Some(holder("third")), None, actor)
        cleared <- core.reassign(issue, Some(holder("third")), None, None, actor)
        released <- core.release(issue, holder("nobody"), None, actor)
      yield
        assert(claimed.isRight, claimed)
        // Claiming what you already hold is idempotent, which keeps a retried claim safe.
        assert(again.isRight, again)
        assertEquals(conflict.left.map(_.code), Left(ErrorCode.ClaimConflict))
        assertEquals(
          conflict.left.toOption.collect { case DomainError.ClaimConflict(who, _) => who.value },
          Some("first")
        )
        assertEquals(notMine.left.map(_.code), Left(ErrorCode.NotHolder))
        assertEquals(wrongFrom.left.map(_.code), Left(ErrorCode.NotHolder))
        assertEquals(moved.map(_.assignee.map(_.value)), Right(Some("third")))
        assertEquals(cleared.map(_.assignee), Right(None))
        // Releasing an issue nobody holds is a no-op, not a rejection.
        assert(released.isRight, released)

  home.test("a closed issue cannot be claimed, released or reassigned"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "The work")
        _ <- core.claim(issue, holder("first"), None, actor)
        _ <- core.close(issue, Resolution.Done, "finished", actor)
        claimed <- core.claim(issue, holder("second"), None, actor)
        released <- core.release(issue, holder("first"), None, actor)
        moved <- core.reassign(issue, Some(holder("first")), None, None, actor)
        after <- core.detail(issue)
      yield
        assertEquals(claimed.left.map(_.code), Left(ErrorCode.IssueClosed))
        assertEquals(released.left.map(_.code), Left(ErrorCode.IssueClosed))
        assertEquals(moved.left.map(_.code), Left(ErrorCode.IssueClosed))
        // The assignee on a closed issue is the record of who resolved it.
        assertEquals(after.row.assignee.map(_.value), Some("first"))

  home.test("the version guard refuses a stale write and moves only for title, body and labels"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "The work")
        first <- core.update(issue, UpdateIssue.nothing.copy(title = Some(title("Renamed"))), actor)
        stale <- core.update(
          issue,
          UpdateIssue.nothing.copy(expectedVersion = Some(Version.first), title = Some(title("Again"))),
          actor
        )
        ranked <- core.update(
          issue,
          UpdateIssue.nothing.copy(rank = Some(RankPlacement.At(Rank.parse(5.0).toOption.get))),
          actor
        )
        labelled <- core.update(issue, UpdateIssue.nothing.copy(labelsAdd = List(label("Bug"))), actor)
        after <- core.detail(issue)
      yield
        assertEquals(first.map(_.version.value), Right(2))
        assertEquals(stale.left.map(_.code), Left(ErrorCode.StaleVersion))
        // Rank has its own ordering rules and no conflict to guard against.
        assertEquals(ranked.map(_.version.value), Right(2))
        assertEquals(labelled.map(_.version.value), Right(3))
        assertEquals(after.row.labels.map(_.value), List("bug"))

  home.test("body edits apply exactly, or are refused by name"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.add(creating("The work", project = Some(project), body = "alpha beta\nalpha gamma"))
        applied <- core.update(issue, edits(BodyEdit("beta", "delta")), actor)
        body <- core.detail(issue).map(_.body.value)
        missing <- core.update(issue, edits(BodyEdit("epsilon", "zeta")), actor)
        ambiguous <- core.update(issue, edits(BodyEdit("alpha", "omega")), actor)
      yield
        assert(applied.isRight, applied)
        assertEquals(body, "alpha delta\nalpha gamma")
        assertEquals(
          missing.left.toOption.collect { case DomainError.EditMismatch(_, kind) => kind },
          Some(EditMismatchKind.Missing)
        )
        assertEquals(
          ambiguous.left.toOption.collect { case DomainError.EditMismatch(_, kind) => kind },
          Some(EditMismatchKind.Ambiguous)
        )

  home.test("rank defaults to the issue number and places between neighbours"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        first <- core.addTo(project, "First")
        second <- core.addTo(project, "Second")
        between <- core.add(creating("Between", project = Some(project), rank = Some(RankPlacement.Before(second))))
        rows <- core.page(s"project:${project.value}").map(_.issues)
      yield
        assertEquals(rows.map(_.rank.value), List(1.0, 1.5, 2.0))
        assertEquals(rows.map(_.id), List(first, between, second))

  home.test("halving until the decimals run out is refused, naming the neighbours"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        first <- core.addTo(project, "First")
        _ <- core.addTo(project, "Second")
        squeeze <- squeezeBetween(core, project, first, 70)
      yield assertEquals(squeeze.map(_.code), Some(ErrorCode.RankPrecision))

  private def edits(edit: BodyEdit): UpdateIssue =
    UpdateIssue.nothing.copy(body = Some(BodyChange.Edits(List(edit))))

  /** Places issues just after the same one over and over, until the midpoint has nowhere left to go. */
  private def squeezeBetween(core: Core, project: ProjectKey, target: IssueId, attempts: Int): IO[Option[DomainError]] =
    (1 to attempts).toList.foldLeftM(Option.empty[DomainError]): (failure, attempt) =>
      failure match
        case Some(_) => IO.pure(failure)
        case None    =>
          core
            .create(
              creating(s"Squeeze $attempt", project = Some(project), rank = Some(RankPlacement.After(target))),
              None,
              actor
            )
            .map(_.left.toOption)
