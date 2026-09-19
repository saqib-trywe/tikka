package tikka.core

import cats.effect.IO
import cats.syntax.all.*
import tikka.shared.*

/** Every filter in the grammar's table, run against a real store. */
class SearchTest extends TempHome, Builders:
  home.test("project, id and status filters select by their own columns"): directory =>
    withCore(directory): core =>
      for
        tik <- core.project("TIK")
        wf2 <- core.project("WF2")
        here <- core.addTo(tik, "Here")
        there <- core.addTo(wf2, "There")
        closed <- core.addTo(tik, "Finished")
        _ <- core.close(closed, Resolution.Done, "done", actor)
        inTik <- core.find("project:TIK")
        both <- core.find("project:TIK,WF2")
        every <- core.find("project:*")
        byId <- core.find(s"id:${there.render}")
        open <- core.find("project:TIK status:open")
        done <- core.find("resolution:done")
      yield
        assertEquals(inTik.toSet, Set(here, closed))
        assertEquals(both.toSet, Set(here, there, closed))
        assertEquals(every.toSet, Set(here, there, closed))
        assertEquals(byId, List(there))
        assertEquals(open, List(here))
        assertEquals(done, List(closed))

  home.test("assignee matches a name, none, any, or a session prefix"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        mine <- core.addTo(project, "Mine")
        theirs <- core.addTo(project, "Theirs")
        free <- core.addTo(project, "Unclaimed")
        _ <- core.claim(mine, holder("saqib/wf-7f3a"), None, actor)
        _ <- core.claim(theirs, holder("someone-else"), None, actor)
        exact <- core.find("assignee:saqib/wf-7f3a")
        prefix <- core.find("assignee:saqib/" + "*")
        nobody <- core.find("assignee:none")
        anyone <- core.find("assignee:any")
        negated <- core.find("-assignee:none")
      yield
        assertEquals(exact, List(mine))
        assertEquals(prefix, List(mine))
        assertEquals(nobody, List(free))
        assertEquals(anyone.toSet, Set(mine, theirs))
        // A negated filter must still match rows where the column is null.
        assertEquals(negated.toSet, Set(mine, theirs))

  home.test("labels match exactly or by prefix, and the colon means nothing"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        bug <- core.add(creating("A bug", project = Some(project), labels = List(label("bug"))))
        map <- core.add(creating("A map", project = Some(project), labels = List(label("wayfinder:map"))))
        ticket <- core.add(creating("A ticket", project = Some(project), labels = List(label("wayfinder:grilling"))))
        exact <- core.find("label:bug")
        prefix <- core.find("label:wayfinder:" + "*")
        either <- core.find("label:bug,wayfinder:map")
        without <- core.find("-label:bug")
      yield
        assertEquals(exact, List(bug))
        assertEquals(prefix.toSet, Set(map, ticket))
        assertEquals(either.toSet, Set(bug, map))
        assertEquals(without.toSet, Set(map, ticket))

  home.test("parent and under walk the hierarchy, under to any depth"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        top <- core.addTo(project, "Top")
        middle <- core.add(creating("Middle", project = Some(project), parent = Some(top)))
        bottom <- core.add(creating("Bottom", project = Some(project), parent = Some(middle)))
        _ <- core.addTo(project, "Unrelated")
        children <- core.find(s"parent:${top.render}")
        descendants <- core.find(s"under:${top.render}")
      yield
        assertEquals(children, List(middle))
        assertEquals(descendants.toSet, Set(middle, bottom), "under reaches any depth, and excludes the root")

  home.test("parent:none finds the top of each hierarchy"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        top <- core.addTo(project, "Top")
        _ <- core.add(creating("Child", project = Some(project), parent = Some(top)))
        loose <- core.addTo(project, "Standalone")
        roots <- core.find("parent:none")
      yield assertEquals(roots, List(top, loose))

  home.test("blocking filters read both directions, and ready is open, unblocked and unclaimed"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        blocker <- core.addTo(project, "Prerequisite")
        waiting <- core.add(creating("Waiting", project = Some(project), blockedBy = List(blocker)))
        claimed <- core.addTo(project, "Claimed")
        _ <- core.claim(claimed, holder("someone"), None, actor)
        blocks <- core.find(s"blocks:${waiting.render}")
        blockedBy <- core.find(s"blocked-by:${blocker.render}")
        blocked <- core.find("blocked")
        unblocked <- core.find("unblocked")
        ready <- core.find("ready")
        spelled <- core.find("status:open unblocked assignee:none")
      yield
        assertEquals(blocks, List(blocker))
        assertEquals(blockedBy, List(waiting))
        assertEquals(blocked, List(waiting))
        assertEquals(unblocked.toSet, Set(blocker, claimed))
        assertEquals(ready, List(blocker))
        // `ready` is exactly its expansion, not an approximation of it.
        assertEquals(ready, spelled)

  home.test("mention filters read the derived edges, in both directions"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        target <- core.addTo(project, "The target")
        source <- core.add(creating("Source", project = Some(project), body = s"see ${target.render}"))
        _ <- core.addTo(project, "Silent")
        mentioning <- core.find(s"mentions:${target.render}")
        mentioned <- core.find(s"mentioned-by:${source.render}")
      yield
        assertEquals(mentioning, List(source))
        assertEquals(mentioned, List(target))

  home.test("text searches title, body and comments, including short and punctuated needles"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        titled <- core.addTo(project, "Reclaimed territory")
        bodied <- core.add(creating("Plain", project = Some(project), body = "runs frontier.sh nightly"))
        commented <- core.addTo(project, "Quiet")
        _ <- core.update(commented, UpdateIssue.nothing.copy(comment = Some("mentions elephants")), actor)
        substring <- core.find("text:claim")
        punctuated <- core.find("text:frontier.sh")
        inComment <- core.find("text:elephant")
        short <- core.find("text:sh")
      yield
        // Trigram matching works like grep: `claim` finds "Reclaimed".
        assertEquals(substring, List(titled))
        assertEquals(punctuated, List(bodied))
        assertEquals(inComment, List(commented))
        // Below three characters the index cannot help, so the scan answers instead.
        assertEquals(short, List(bodied))

  home.test("time filters count back from the daemon's clock"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        issue <- core.addTo(project, "Recent")
        _ <- core.claim(issue, holder("someone"), None, actor)
        recent <- core.find("created-after:1d")
        old <- core.find("created-before:1d")
        claimed <- core.find("claimed-after:1h")
        stale <- core.find("claimed-before:1h")
        updated <- core.find("updated-after:30m")
        // A week is a unit the parser accepts and its own error message advertises, so it has to reach the store.
        weekly <- core.find("updated-after:1w")
        lastWeek <- core.find("created-before:2w")
      yield
        assertEquals(weekly, List(issue))
        assertEquals(lastWeek, Nil)
        assertEquals(recent, List(issue))
        assertEquals(old, Nil)
        assertEquals(claimed, List(issue))
        // Finding stale claims is the whole mechanism for unwedging them, since claims never expire.
        assertEquals(stale, Nil)
        assertEquals(updated, List(issue))

  home.test("sorts order by rank, creation, update and closing"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        first <- core.addTo(project, "First")
        second <- core.addTo(project, "Second")
        third <- core.addTo(project, "Third")
        _ <- core.close(second, Resolution.Done, "done", actor)
        _ <- core.update(first, UpdateIssue.nothing.copy(comment = Some("touched last")), actor)
        byRank <- core.find("")
        byRankDesc <- core.find("sort:rank-desc")
        byUpdated <- core.find("sort:updated")
        byClosed <- core.find("sort:closed")
      yield
        assertEquals(byRank, List(first, second, third))
        assertEquals(byRankDesc, List(third, second, first))
        assertEquals(byUpdated.head, first, "the most recently touched issue comes first")
        assertEquals(byClosed.head, second, "issues with no closing time sort last")
        assertEquals(byClosed.toSet, Set(first, second, third))

  home.test("paging walks the whole result set exactly once"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        ids <- (1 to 7).toList.traverse(number => core.addTo(project, s"Issue $number"))
        walked <- walk(core, "", limit = 3)
      yield
        assertEquals(walked, ids)
        assertEquals(walked.distinct.size, walked.size)

  home.test("an issue that did not change appears exactly once, even when others are written mid-page"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        ids <- (1 to 5).toList.traverse(number => core.addTo(project, s"Issue $number"))
        first <- core.page("", limit = 2)
        // A concurrent writer adds an issue and touches one already seen.
        added <- core.addTo(project, "Written mid-page")
        _ <- core.update(ids.head, UpdateIssue.nothing.copy(comment = Some("touched")), actor)
        rest <- walkFrom(core, "", first.nextCursor, limit = 2, seen = Nil)
      yield
        assertEquals(rest.count(_ == ids.head), 0, "an issue already paged past is not repeated")
        assertEquals(rest.toSet, Set(ids(2), ids(3), ids(4), added))

  home.test("a cursor is refused when it is damaged or belongs to another sort"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        _ <- core.addTo(project, "First")
        _ <- core.addTo(project, "Second")
        page <- core.page("", limit = 1)
        cursor = page.nextCursor.getOrElse(fail("expected a cursor"))
        wrongSort <- core.search("sort:created", None, Some(cursor), 50)
        damaged <- core.search("", None, Some("not-a-cursor"), 50)
      yield
        assertEquals(wrongSort.left.map(_.code), Left(ErrorCode.InvalidArgument))
        assertEquals(damaged.left.map(_.code), Left(ErrorCode.InvalidArgument))

  home.test("a page holds at most the contract's maximum, and asking for more is refused"): directory =>
    withCore(directory): core =>
      for
        project <- core.project("TIK")
        _ <- core.addTo(project, "Only")
        tooMany <- core.search("", None, None, Core.maxLimit + 1)
        none <- core.search("", None, None, 0)
      yield
        assertEquals(tooMany.left.map(_.code), Left(ErrorCode.InvalidArgument))
        assertEquals(none.left.map(_.code), Left(ErrorCode.InvalidArgument))

  home.test("a bound connection scopes an unscoped query, and the effective query says so"): directory =>
    withCore(directory): core =>
      for
        tik <- core.project("TIK")
        wf2 <- core.project("WF2")
        here <- core.addTo(tik, "Here")
        there <- core.addTo(wf2, "There")
        bound <- core.search("ready", Some(tik), None, 50).map(_.fold(error => sys.error(error.toString), identity))
        explicit <- core
          .search("project:WF2", Some(tik), None, 50)
          .map(_.fold(error => sys.error(error.toString), identity))
      yield
        assertEquals(bound.issues.map(_.id), List(here))
        assertEquals(bound.effectiveQuery, "project:TIK ready")
        // An explicit project always wins over the binding.
        assertEquals(explicit.issues.map(_.id), List(there))
        assertEquals(explicit.effectiveQuery, "project:WF2")

  home.test("a query tikka cannot read is refused with the token and a suggestion"): directory =>
    withCore(directory): core =>
      for
        _ <- core.project("TIK")
        error <- core.refuse("statu:open")
      yield
        assertEquals(error.code, ErrorCode.InvalidQuery)
        assertEquals(
          error match
            case DomainError.InvalidQuery(token, _, suggestions) => Some((token, suggestions.contains("status")))
            case _                                               => None,
          Some(("statu:open", true))
        )

  private def walk(core: Core, query: String, limit: Int): IO[List[IssueId]] =
    core
      .page(query, limit = limit)
      .flatMap(page => walkFrom(core, query, page.nextCursor, limit, page.issues.map(_.id)))

  private def walkFrom(
      core: Core,
      query: String,
      cursor: Option[String],
      limit: Int,
      seen: List[IssueId]
  ): IO[List[IssueId]] =
    cursor match
      case None       => IO.pure(seen)
      case Some(next) =>
        core
          .page(query, cursor = Some(next), limit = limit)
          .flatMap(page => walkFrom(core, query, page.nextCursor, limit, seen ++ page.issues.map(_.id)))
