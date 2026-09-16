package tikka.shared

/** The grammar's filter table, checked on every platform the parser runs on: daemon, browser and CLI. */
class QueryParserTest extends munit.FunSuite:
  private def parse(text: String): Query = QueryParser.parse(text).fold(error => fail(error.toString), identity)

  private def filters(text: String): List[Filter] = parse(text).terms.map(_.filter)

  private def id(value: String): IssueId = IssueId.parse(value).fold(sys.error, identity)

  private def key(value: String): ProjectKey = ProjectKey.parse(value).fold(sys.error, identity)

  private def refusal(text: String): QueryError =
    QueryParser.parse(text).fold(identity, parsed => fail(s"expected a rejection, got ${parsed.render}"))

  test("an empty query is valid and matches everything in scope"):
    assertEquals(parse(""), Query.everything)
    assertEquals(parse("   "), Query.everything)

  test("project takes keys or a star"):
    assertEquals(filters("project:TIK"), List(Filter.Project(ProjectScope.Keys(List(key("TIK"))))))
    assertEquals(filters("project:TIK,WF2"), List(Filter.Project(ProjectScope.Keys(List(key("TIK"), key("WF2"))))))
    assertEquals(filters("project:*"), List(Filter.Project(ProjectScope.Every)))

  test("status and resolution take their words"):
    assertEquals(filters("status:open"), List(Filter.Status(List(StatusValue.Open))))
    assertEquals(filters("status:open,closed"), List(Filter.Status(List(StatusValue.Open, StatusValue.Closed))))
    assertEquals(filters("resolution:dropped"), List(Filter.Resolution(List(Resolution.Dropped))))

  test("assignee takes a name, none, any, or a session prefix"):
    assertEquals(filters("assignee:none"), List(Filter.Assignee(List(AssigneeMatch.Nobody))))
    assertEquals(filters("assignee:any"), List(Filter.Assignee(List(AssigneeMatch.Anyone))))
    assertEquals(
      filters("assignee:saqib/wf-7f3a"),
      List(Filter.Assignee(List(AssigneeMatch.Named(NameMatch.Exact("saqib/wf-7f3a")))))
    )
    assertEquals(
      filters("assignee:saqib/" + "*"),
      List(Filter.Assignee(List(AssigneeMatch.Named(NameMatch.Prefix("saqib/")))))
    )

  test("label matches exactly or by prefix, and the colon means nothing to tikka"):
    assertEquals(filters("label:bug"), List(Filter.Label(List(NameMatch.Exact("bug")))))
    assertEquals(filters("label:wayfinder:" + "*"), List(Filter.Label(List(NameMatch.Prefix("wayfinder:")))))

  test("edge and mention filters take issue ids"):
    assertEquals(filters("parent:TIK-1"), List(Filter.Parent(List(id("TIK-1")))))
    assertEquals(filters("under:TIK-1"), List(Filter.Under(List(id("TIK-1")))))
    assertEquals(filters("blocks:TIK-2"), List(Filter.Blocks(List(id("TIK-2")))))
    assertEquals(filters("blocked-by:TIK-3"), List(Filter.BlockedBy(List(id("TIK-3")))))
    assertEquals(filters("mentions:TIK-4"), List(Filter.Mentions(List(id("TIK-4")))))
    assertEquals(filters("mentioned-by:TIK-5"), List(Filter.MentionedBy(List(id("TIK-5")))))
    assertEquals(filters("id:TIK-6,TIK-7"), List(Filter.Ids(List(id("TIK-6"), id("TIK-7")))))

  test("blocked, unblocked and ready are bare predicates"):
    assertEquals(filters("blocked"), List(Filter.Blocked))
    assertEquals(filters("unblocked"), List(Filter.Unblocked))
    assertEquals(filters("ready"), List(Filter.Ready))

  test("time filters take a duration or a date"):
    assertEquals(
      filters("claimed-before:3d"),
      List(Filter.Time(TimeField.Claimed, TimeDirection.Before, Moment.Ago(3, TimeUnit.Days)))
    )
    assertEquals(
      filters("updated-after:30m"),
      List(Filter.Time(TimeField.Updated, TimeDirection.After, Moment.Ago(30, TimeUnit.Minutes)))
    )
    assertEquals(
      filters("created-after:2026-09-16"),
      List(Filter.Time(TimeField.Created, TimeDirection.After, Moment.OnDate("2026-09-16")))
    )
    assertEquals(
      filters("closed-before:2026-09-16T10:00:00Z"),
      List(Filter.Time(TimeField.Closed, TimeDirection.Before, Moment.AtDateTime("2026-09-16T10:00:00Z")))
    )

  test("text takes a phrase, quoted when it has spaces"):
    assertEquals(filters("text:claim"), List(Filter.Text("claim")))
    assertEquals(filters("""text:"two words""""), List(Filter.Text("two words")))
    // The token is taken as written, so a query can search for punctuation.
    assertEquals(filters("text:frontier.sh"), List(Filter.Text("frontier.sh")))

  test("a leading minus negates a whole filter"):
    assertEquals(parse("-assignee:none").terms, List(Term(negated = true, Filter.Assignee(List(AssigneeMatch.Nobody)))))
    assertEquals(parse("-label:bug").terms.map(_.negated), List(true))

  test("filters are ANDed in the order written"):
    assertEquals(
      filters("ready label:bug -assignee:none"),
      List(Filter.Ready, Filter.Label(List(NameMatch.Exact("bug"))), Filter.Assignee(List(AssigneeMatch.Nobody)))
    )

  test("sort defaults to rank ascending, and times default to newest first"):
    assertEquals(parse("").sort, Sort.default)
    assertEquals(parse("sort:rank").sort, Sort(SortField.Rank, SortDirection.Ascending))
    assertEquals(parse("sort:updated").sort, Sort(SortField.Updated, SortDirection.Descending))
    assertEquals(parse("sort:created-asc").sort, Sort(SortField.Created, SortDirection.Ascending))
    assertEquals(parse("sort:closed-desc").sort, Sort(SortField.Closed, SortDirection.Descending))

  test("every query prints back to itself"):
    val queries = List(
      "",
      "ready",
      "project:TIK status:open",
      "project:*",
      "status:open,closed resolution:done",
      "assignee:none label:bug,chore",
      "label:wayfinder:" + "*",
      "assignee:saqib/" + "*",
      "parent:TIK-1 under:TIK-2 blocks:TIK-3 blocked-by:TIK-4",
      "mentions:TIK-5 mentioned-by:TIK-6 id:TIK-7,TIK-8",
      "blocked unblocked",
      "claimed-before:3d updated-after:2026-09-16",
      "closed-before:2026-09-16T10:00:00Z",
      "text:claim",
      """text:"two words"""",
      "-label:bug -ready",
      "ready sort:updated-asc",
      "sort:created-desc"
    )
    queries.foreach: text =>
      val once = parse(text)
      val twice = parse(once.render)
      assertEquals(twice, once, s"'$text' did not round-trip (printed as '${once.render}')")

  test("an unknown filter is refused, with the nearest names suggested"):
    val error = refusal("statu:open")
    assertEquals(error.token, "statu:open")
    assertEquals(error.position, 0)
    assert(error.suggestions.contains("status"), error.suggestions)

  test("a bad value is refused, naming what was expected"):
    assert(refusal("status:maybe").reason.contains("expected open or closed"))
    assert(refusal("resolution:finished").reason.contains("expected done or dropped"))
    assert(refusal("parent:TIK").reason.contains("TIK-42"))
    assert(refusal("project:tik").reason.contains("project key"))
    assert(refusal("claimed-before:soon").reason.contains("duration"))
    assert(refusal("sort:title").reason.contains("expected rank, created, updated or closed"))
    assert(refusal("sort:rank-sideways").reason.contains("expected asc or desc"))

  test("a predicate with a value, or a filter without one, is refused"):
    assert(refusal("ready:true").reason.contains("takes no value"))
    assert(refusal("label").reason.contains("needs a value"))
    assert(refusal("label:").reason.contains("needs a value"))

  test("a query carries only one sort, and sort cannot be negated"):
    assert(refusal("sort:rank sort:created").reason.contains("only one sort"))
    assert(refusal("-sort:rank").reason.contains("cannot be negated"))

  test("the position points at the token that failed"):
    assertEquals(refusal("ready blocked statu:open").position, 14)
