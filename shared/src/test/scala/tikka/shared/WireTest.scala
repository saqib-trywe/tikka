package tikka.shared

import io.circe.parser.decode
import io.circe.syntax.*
import tikka.shared.Wire.*

/** The wire mapping, the text renderer and the line diff: the pieces every surface shares, on every platform. */
class WireTest extends munit.FunSuite:
  private val row = RowOut(
    "TIK-42",
    "Choose the persistence engine",
    "open",
    None,
    Some("scala-survey"),
    List("grilling"),
    None,
    blocked = true,
    rank = 42.0,
    updated = "2026-09-17T10:00:00.000Z"
  )

  test("a row renders as one line: id, state, title and labels"):
    assertEquals(
      Render.row(row),
      "TIK-42  open · claimed by scala-survey · blocked  Choose the persistence engine  [grilling]"
    )

  test("a closed row names its resolution"):
    assertEquals(
      Render.row(
        row.copy(status = "closed", resolution = Some("done"), assignee = None, blocked = false, labels = Nil)
      ),
      "TIK-42  closed done  Choose the persistence engine"
    )

  test("wire fields are snake_case, and absent optionals stay absent once nulls are dropped"):
    val json = SearchOut("project:TIK ready", List(row), None, hasMore = false).asJson
    assert(json.hcursor.downField("effective_query").succeeded)
    assert(json.hcursor.downField("has_more").succeeded)
    assert(!json.deepDropNullValues.hcursor.downField("next_cursor").succeeded)

  test("parent is absent, cleared by null, or set, and the three stay distinct"):
    assertEquals(decode[UpdateIn]("{}").map(_.parent), Right(Tri.Missing))
    assertEquals(decode[UpdateIn]("""{"parent": null}""").map(_.parent), Right(Tri.Cleared))
    assertEquals(decode[UpdateIn]("""{"parent": "TIK-1"}""").map(_.parent), Right(Tri.Set("TIK-1")))

  test("an update maps to the core's command, clearing the parent on null"):
    val update = decode[UpdateIn]("""{"parent": null, "labels_add": ["Bug"], "expected_version": 3}""").left
      .map(_.toString)
      .flatMap(in => Requests.update(in).left.map(_.toString))
    assertEquals(update.map(_.parent), Right(Some(ParentChange.Clear)))
    assertEquals(update.map(_.labelsAdd.map(_.value)), Right(List("bug")))
    assertEquals(update.map(_.expectedVersion.map(_.value)), Right(Some(3)))

  test("conflicting arguments are refused by name"):
    val ranks = Requests.create(CreateIn("Title", rank = Some(1.0), rankBefore = Some("TIK-1")))
    val body = Requests.update(UpdateIn(body = Some("new"), bodyEdits = Some(List(BodyEditIn("a", "b")))))
    assertEquals(ranks.left.map(_.code), Left(ErrorCode.InvalidArgument))
    assertEquals(body.left.map(_.code), Left(ErrorCode.InvalidArgument))

  test("reassigning to none names nobody"):
    assertEquals(Requests.holder("to", "none"), Right(None))
    assertEquals(Requests.holder("to", "saqib/wf-1").map(_.map(_.value)), Right(Some("saqib/wf-1")))

  test("a rejection's error body leads with the code and message, then the facts"):
    val body =
      Rejection.of(DomainError.Cycle(List(IssueId.parse("TIK-4").toOption.get, IssueId.parse("TIK-9").toOption.get)))
    val json = body.asJson
    assertEquals(json.hcursor.keys.map(_.toList.take(2)), Some(List("error", "message")))
    assertEquals(json.hcursor.get[List[String]]("path"), Right(List("TIK-4", "TIK-9")))
    assert(body.message.contains("TIK-4 → TIK-9"))

  test("the line diff marks removed and added lines and keeps the rest"):
    val lines = LineDiff.lines("one\ntwo\nthree", "one\n2\nthree\nfour")
    assertEquals(
      lines,
      List(
        LineDiff.Line.Same("one"),
        LineDiff.Line.Removed("two"),
        LineDiff.Line.Added("2"),
        LineDiff.Line.Same("three"),
        LineDiff.Line.Added("four")
      )
    )

  test("a unified diff keeps context near changes and elides the rest"):
    val before = (1 to 10).map(n => s"line $n").mkString("\n")
    val after = before.replace("line 9", "line nine")
    val diff = LineDiff.unified(before, after)
    assert(diff.startsWith("  …"), diff)
    assert(diff.contains("- line 9\n+ line nine"), diff)
    assert(!diff.contains("line 2"), diff)

  test("an event renders its header, a body diff, and its comment"):
    val event = EventOut(
      1042,
      "2026-09-15T10:02:00.000Z",
      "mcp:claude-code",
      "TIK-42",
      Nil,
      List(ChangeOut("body", "set", Some("old line"), Some("new line")), ChangeOut("label", "add", None, Some("bug"))),
      Some("tidying")
    )
    val text = Render.event(event)
    assert(text.startsWith("#1042 2026-09-15T10:02:00.000Z mcp:claude-code on TIK-42"), text)
    assert(text.contains("- old line") && text.contains("+ new line"), text)
    assert(text.contains("+bug"), text)
    assert(text.contains("tidying"), text)
