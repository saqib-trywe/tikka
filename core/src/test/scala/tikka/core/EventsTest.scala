package tikka.core

import tikka.shared.*

/** The event log's rules: one write is one event, and an event lands on every timeline it belongs to. */
class EventsTest extends TempHome, Builders:
  home.test("one write is one event, however many fields it touched"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "The work")
        _ <- core.update(
          issue,
          UpdateIssue.nothing
            .copy(title = Some(title("Renamed")), labelsAdd = List(label("bug")), comment = Some("tidying")),
          actor
        )
        events <- core.events(issue)
      yield
        assertEquals(events.size, 2, "the create, then the update")
        val update = events.last
        assertEquals(update.changes.map(_.field).toSet, Set(ChangeField.Title, ChangeField.Label))
        assertEquals(update.comment, Some("tidying"))
        assertEquals(update.actor.render, "cli")

  home.test("a create records each initial field, and a comment on its own is an event with no changes"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.add(creating("The work", project = Some(project), body = "why", labels = List(label("bug"))))
        _ <- core.update(issue, UpdateIssue.nothing.copy(comment = Some("just a remark")), actor)
        events <- core.events(issue)
        detail <- core.detail(issue)
      yield
        assertEquals(events.head.changes.map(_.field), List(ChangeField.Title, ChangeField.Body, ChangeField.Label))
        assertEquals(events.last.changes, Nil)
        assertEquals(detail.comments.map(_.text), List("just a remark"))

  home.test("a write that changes nothing records no event and does not move updated"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "The work")
        before <- core.detail(issue).map(_.row.updated)
        _ <- core.update(issue, UpdateIssue.nothing.copy(title = Some(title("The work"))), actor)
        events <- core.events(issue)
        after <- core.detail(issue).map(_.row.updated)
      yield
        assertEquals(events.size, 1)
        assertEquals(after.value, before.value)

  home.test("an edge change appears on both timelines, and moves both updated times"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        blocker <- core.addTo(project, "Prerequisite")
        before <- core.detail(blocker).map(_.row.updated)
        work <- core.addTo(project, "The work")
        _ <- core.blockOn(work, blocker)
        events <- core.events(blocker)
        after <- core.detail(blocker).map(_.row.updated)
      yield
        assertEquals(events.size, 2, "its own creation, and the edge written from the other issue")
        assertEquals(events.last.subject, work, "the event still belongs to the issue written to")
        assert(after.value >= before.value)

  home.test("closing a blocker appears on the timeline of each issue it frees"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        blocker <- core.addTo(project, "Prerequisite")
        follower <- core.add(creating("Waiting", project = Some(project), blockedBy = List(blocker)))
        _ <- core.close(blocker, Resolution.Done, "finished", actor)
        events <- core.events(follower)
      yield
        assertEquals(events.size, 2)
        assertEquals(events.last.subject, blocker)
        assertEquals(events.last.changes.map(_.field), List(ChangeField.Status, ChangeField.Resolution))

  home.test("a title change records the text either side of it"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "Before")
        _ <- core.update(issue, UpdateIssue.nothing.copy(title = Some(title("After"))), actor)
        events <- core.events(issue)
      yield
        val change = events.last.changes.head
        assertEquals(change.old, Some("Before"))
        assertEquals(change.updated, Some("After"))

  home.test("the event sequence orders timelines across issues"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        first <- core.addTo(project, "First")
        second <- core.addTo(project, "Second")
        firstEvents <- core.events(first)
        secondEvents <- core.events(second)
      yield assert(firstEvents.head.seq.value < secondEvents.head.seq.value)
