package tikka.prose

import tikka.shared.IssueId
import tikka.shared.ProjectKey

/** The detection rules from "Settle body and mention conventions". */
class MentionsTest extends munit.FunSuite:
  private val tik = ProjectKey.parse("TIK").toOption.get
  private val wf2 = ProjectKey.parse("WF2").toOption.get
  private val known = Set(tik, wf2)
  private def id(value: String): IssueId = IssueId.parse(value).toOption.get

  private def detect(markdown: String, self: Option[IssueId] = None): Set[IssueId] =
    Mentions.detect(markdown, known, self)

  test("a bare id in prose is a mention"):
    assertEquals(detect("Follow-up to TIK-3."), Set(id("TIK-3")))

  test("punctuation around an id does not stop it counting"):
    assertEquals(detect("see (TIK-42), TIK-7."), Set(id("TIK-42"), id("TIK-7")))

  test("ids joined to other characters are not mentions"):
    assertEquals(detect("xTIK-42 TIK-42s TIK-42-fix TIK-042 tik-42"), Set.empty)

  test("inline code and fenced blocks never count"):
    val markdown = """Compare `TIK-1` with this:
                     |
                     |```
                     |TIK-2 failed
                     |```
                     |
                     |    TIK-3 indented
                     |
                     |but TIK-4 counts.
                     |""".stripMargin
    assertEquals(detect(markdown), Set(id("TIK-4")))

  test("a link into an issue page counts, whatever the host"):
    assertEquals(detect("[the ticket](/i/TIK-9)"), Set(id("TIK-9")))
    assertEquals(detect("[the ticket](http://127.0.0.1:7017/i/WF2-5)"), Set(id("WF2-5")))

  test("mentions cross projects"):
    assertEquals(detect("TIK-1 and WF2-2"), Set(id("TIK-1"), id("WF2-2")))

  test("a token whose project does not exist is not a mention"):
    assertEquals(detect("UTF-8 and ISO-8601 and SHA-256 and ZZZ-1"), Set.empty)

  test("an issue never mentions itself"):
    assertEquals(detect("TIK-1 relates to TIK-2", self = Some(id("TIK-1"))), Set(id("TIK-2")))

  test("an id that does not exist yet is still a mention"):
    assertEquals(detect("blocked on TIK-9999"), Set(id("TIK-9999")))
