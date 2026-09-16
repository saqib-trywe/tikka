package tikka.core

import cats.syntax.all.*
import tikka.shared.*

/** The race the daemon exists to prevent: many sessions reaching for the same issue at once. */
class ClaimRaceTest extends TempHome, Builders:
  home.test("exactly one of many concurrent claims wins, and the losers are told who holds it"): directory =>
    withCore(directory): core =>
      val sessions = (1 to 24).toList.map(number => holder(s"saqib/wf-$number"))
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "Contended work")
        outcomes <- sessions.parTraverse(session => core.claim(issue, session, None, actor))
        detail <- core.detail(issue)
        events <- core.events(issue)
      yield
        val winners = outcomes.collect { case Right(claimed) => claimed.row.assignee.map(_.value) }.flatten
        val losers = outcomes.collect { case Left(error) => error }
        assertEquals(winners.size, 1, "one session takes the issue")
        assertEquals(losers.size, sessions.size - 1)
        assert(losers.forall(_.code == ErrorCode.ClaimConflict), losers.map(_.code).distinct)
        assertEquals(
          losers.collect { case DomainError.ClaimConflict(who, _) => who.value }.distinct,
          winners.distinct,
          "every loser is told the same holder"
        )
        assertEquals(detail.row.assignee.map(_.value), winners.headOption)
        // The rejections leave no trace: the log records changes, not attempts.
        assertEquals(events.count(_.changes.exists(_.field == ChangeField.Assignee)), 1)
