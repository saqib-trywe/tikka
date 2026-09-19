package tikka.core

import cats.effect.IO
import cats.syntax.all.*
import tikka.shared.*

import scala.concurrent.duration.*

/** What the store must still do once there is more than a handful of anything in it.
  *
  * The bounds here are deliberately loose — this is a gate against work that grows with the wrong thing, not a
  * benchmark. Both cases below were regressions found by review rather than by a test, which is why they are here.
  */
class ScaleTest extends TempHome, Builders:
  /** Each diamond doubles the number of distinct paths through the blocking graph while adding three issues, so 28
    * of them is 85 issues and about 270 million paths. Measured against sqlite3, enumerating paths runs at roughly
    * half a million a second, which puts this graph at several minutes; visiting each issue once is instant.
    */
  private val diamonds = 28

  private val budget: FiniteDuration = 30.seconds

  home.test("a cycle check walks issues, not the paths between them"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        chain <- diamondChain(core, project)
        (head, tail) = chain
        elsewhere <- core.addTo(project, "Nothing to do with the chain")
        // The common case, and the expensive one: everything reachable from the head must be visited to answer "no".
        accepted <- timed(core.blockOn(head, elsewhere))
        (settled, allowed) = accepted
        refused <- timed(core.blockOn(head, tail))
        (looped, rejected) = refused
      yield
        assert(allowed.isRight, allowed)
        assert(settled < budget, s"a blocker on an unrelated issue took $settled")
        assertEquals(rejected.left.map(_.code), Left(ErrorCode.Cycle))
        assert(looped < budget, s"the cycle rejection took $looped")
        val path = rejected.left.toOption.collect { case DomainError.Cycle(ids) => ids }
        // The loop is named end to end, shortest way round: two hops through each diamond, then back to the head.
        assertEquals(path.map(_.head), Some(head))
        assertEquals(path.map(_.last), Some(head))
        assertEquals(path.map(_.size), Some(2 * diamonds + 2))
        assertEquals(path.map(_.contains(tail)), Some(true))

  home.test("a full page of issues costs a fixed number of queries, not a few per row"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        parent <- core.addTo(project, "The effort")
        _ <- (1 to Core.maxLimit).toList.traverse_(n => busyIssue(core, project, parent, n))
        // `sort:updated` orders on the event log rather than a stored column, so the page pays for that too. The
        // label keeps the parent itself out of the page, leaving exactly the issues seeded below.
        page <- timed(core.page("label:bug sort:updated", limit = Core.maxLimit))
        (took, found) = page
      yield
        assertEquals(found.issues.size, Core.maxLimit)
        assert(found.issues.forall(_.labels.size == 3), "every row carries its labels")
        assert(found.issues.forall(_.parent.contains(parent)), "every row names its parent")
        assert(found.issues.forall(!_.blocked), "every row knows nothing blocks it")
        assert(took < budget, s"a ${Core.maxLimit}-issue page took $took")

  /** `diamonds` diamonds in a row: each one's head blocks two issues, and both of those block the next head. Built
    * through `blocked_by` at creation, which needs no cycle check — a brand new issue cannot be in a loop.
    */
  private def diamondChain(core: Core, project: ProjectKey): IO[(IssueId, IssueId)] =
    core
      .addTo(project, "Head of the chain")
      .flatMap: head =>
        (1 to diamonds).toList
          .foldLeftM(head): (upstream, n) =>
            for
              left <- core.add(creating(s"Left $n", project = Some(project), blockedBy = List(upstream)))
              right <- core.add(creating(s"Right $n", project = Some(project), blockedBy = List(upstream)))
              next <- core.add(creating(s"Head $n", project = Some(project), blockedBy = List(left, right)))
            yield next
          .map(tail => (head, tail))

  /** An issue with everything a row has to gather: labels, a parent, and a timeline longer than one event. */
  private def busyIssue(core: Core, project: ProjectKey, parent: IssueId, n: Int): IO[Unit] =
    core
      .add(
        creating(
          s"Issue $n",
          project = Some(project),
          labels = List(label("bug"), label("area:store"), label(s"batch:${n % 7}")),
          parent = Some(parent)
        )
      )
      .flatMap: issue =>
        core
          .update(issue, UpdateIssue.nothing.copy(comment = Some(s"a remark on $n")), actor)
          .void

  private def timed[A](program: IO[A]): IO[(FiniteDuration, A)] =
    (IO.monotonic, program, IO.monotonic).mapN((started, value, finished) => (finished - started, value))
