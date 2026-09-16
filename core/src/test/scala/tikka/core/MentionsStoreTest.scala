package tikka.core

import tikka.shared.*

/** Mentions as the store keeps them: derived from current text, forward references included. */
class MentionsStoreTest extends TempHome, Builders:
  home.test("a mention of an issue that does not exist yet becomes a backlink when it does"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        source <- core.add(creating("Planning", project = Some(project), body = "blocked on TIK-9 for now"))
        _ <- core.addTo(project, "Filler 2")
        _ <- core.addTo(project, "Filler 3")
        _ <- core.addTo(project, "Filler 4")
        _ <- core.addTo(project, "Filler 5")
        _ <- core.addTo(project, "Filler 6")
        _ <- core.addTo(project, "Filler 7")
        _ <- core.addTo(project, "Filler 8")
        target <- core.addTo(project, "The awaited issue")
        detail <- core.detail(target)
      yield
        assertEquals(target.render, "TIK-9")
        assertEquals(detail.backlinks.map(_.id), List(source))

  home.test("creating a project rescans existing prose for its key"): directory =>
    withCore(directory): core =>
      for
        tik <- core.project("TIK")
        source <- core.add(creating("Planning", project = Some(tik), body = "see WF2-1, once that exists"))
        before <- core.detail(source)
        wf2 <- core.project("WF2")
        target <- core.addTo(wf2, "The other project's first issue")
        after <- core.detail(target)
      yield
        // Before WF2 exists, WF2-1 is just text: a token counts only if its project does.
        assertEquals(before.mentions, Nil)
        assertEquals(target.render, "WF2-1")
        assertEquals(after.backlinks.map(_.id), List(source))

  home.test("mentions follow the current text, and comments keep theirs"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        target <- core.addTo(project, "The target")
        source <- core.add(creating("Source", project = Some(project), body = s"relates to ${target.render}"))
        _ <- core.update(source, UpdateIssue.nothing.copy(comment = Some(s"also see ${target.render}")), actor)
        before <- core.detail(target)
        _ <- core.update(source, UpdateIssue.nothing.copy(body = Some(BodyChange.Replace(Body("nothing here")))), actor)
        after <- core.detail(target)
        mentions <- core.detail(source).map(_.mentions.map(_.id))
      yield
        assertEquals(before.backlinks.map(_.id), List(source))
        // Editing the id out of the body removes its mention, but the comment's mention is permanent.
        assertEquals(after.backlinks.map(_.id), List(source))
        assertEquals(mentions, List(target))

  home.test("an id in a title counts, an id in code does not, and an issue never mentions itself"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        target <- core.addTo(project, "The target")
        titled <- core.add(creating(s"Follow-up to ${target.render}", project = Some(project)))
        coded <- core.add(
          creating(
            "Log paste",
            project = Some(project),
            body = s"`${target.render}` and:\n\n```\n${target.render} failed\n```"
          )
        )
        detail <- core.detail(target)
        selfRef <- core.detail(titled).map(_.mentions.map(_.id))
      yield
        assertEquals(detail.backlinks.map(_.id), List(titled))
        assert(!detail.backlinks.map(_.id).contains(coded), "code never counts")
        assertEquals(selfRef, List(target))

  home.test("a mention leaves the mentioned issue's timeline quiet"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        target <- core.addTo(project, "The target")
        before <- core.detail(target).map(_.row.updated)
        _ <- core.add(creating("Source", project = Some(project), body = s"see ${target.render}"))
        after <- core.detail(target)
        events <- core.events(target)
      yield
        assertEquals(after.row.updated.value, before.value, "a backlink does not move updated")
        assertEquals(events.size, 1, "only the target's own creation")
