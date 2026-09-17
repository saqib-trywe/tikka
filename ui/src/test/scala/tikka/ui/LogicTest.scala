package tikka.ui

import tikka.shared.Wire.EventOut
import tikka.shared.Wire.RowOut

/** The view logic that does not touch the DOM. */
class LogicTest extends munit.FunSuite:
  private def row(
      id: String,
      status: String = "open",
      assignee: Option[String] = None,
      blocked: Boolean = false
  ): RowOut =
    RowOut(id, s"Issue $id", status, None, assignee, Nil, None, blocked, 1.0, "2026-09-17T10:00:00.000Z")

  private def event(issue: String, related: List[String] = Nil): EventOut =
    EventOut(1, "2026-09-17T10:00:00.000Z", "mcp:claude-code", issue, related, Nil, None)

  test("an event concerns an issue when it is its subject or among the issues it touched"):
    assert(Logic.concerns(event("TIK-1"), "TIK-1"))
    assert(Logic.concerns(event("TIK-2", related = List("TIK-1")), "TIK-1"))
    assert(!Logic.concerns(event("TIK-2"), "TIK-1"))

  test("moving an issue places it on the far side of its neighbour"):
    val rows = List(row("TIK-1"), row("TIK-2"), row("TIK-3"))
    assertEquals(Logic.placement(rows, "TIK-2", Move.Up), Some(Placement("TIK-1", before = true)))
    assertEquals(Logic.placement(rows, "TIK-2", Move.Down), Some(Placement("TIK-3", before = false)))

  test("the ends of a list, and an issue not in it, cannot move"):
    val rows = List(row("TIK-1"), row("TIK-2"))
    assertEquals(Logic.placement(rows, "TIK-1", Move.Up), None)
    assertEquals(Logic.placement(rows, "TIK-2", Move.Down), None)
    assertEquals(Logic.placement(rows, "TIK-9", Move.Up), None)

  test("a row reads as its state, its holder and whether it is blocked"):
    assertEquals(Logic.state(row("TIK-1")), "open")
    assertEquals(
      Logic.state(row("TIK-1", assignee = Some("saqib/wf-1"), blocked = true)),
      "open · claimed by saqib/wf-1 · blocked"
    )
    assertEquals(Logic.state(row("TIK-1", status = "closed").copy(resolution = Some("done"))), "closed done")

  test("a row carries classes for open, closed, claimed and blocked"):
    assertEquals(Logic.rowClasses(row("TIK-1")), List("row", "open"))
    assertEquals(
      Logic.rowClasses(row("TIK-1", "closed", Some("x"), blocked = true)),
      List("row", "closed", "claimed", "blocked")
    )

  test("the update bar counts what arrived, and says nothing when nothing has"):
    assertEquals(Logic.updateBar(0), None)
    assertEquals(Logic.updateBar(1), Some("1 update — refresh"))
    assertEquals(Logic.updateBar(4), Some("4 updates — refresh"))
