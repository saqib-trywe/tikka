package tikka.prose

import tikka.shared.IssueId
import tikka.shared.ProjectKey

/** Rendering for the web UI: mentions linked exactly where they count, and nothing unsafe let through. */
class ProseTest extends munit.FunSuite:
  private val known = Set(ProjectKey.parse("TIK").toOption.get)
  private def id(value: String): IssueId = IssueId.parse(value).toOption.get

  private val targets = Map(
    id("TIK-1") -> MentionTarget("Open work", closed = false),
    id("TIK-2") -> MentionTarget("Finished work", closed = true)
  )

  private def render(markdown: String, self: Option[IssueId] = None): String =
    Prose.html(markdown, known, self, targets.get)

  test("markdown renders to HTML"):
    val html = render("# Heading\n\nSome **bold** text.")
    assert(html.contains("<h1"), html)
    assert(html.contains("<strong>bold</strong>"), html)

  test("a mention becomes a link to the issue, styled by status, with its title as the tooltip"):
    val html = render("See TIK-1 and TIK-2.")
    assert(html.contains("""<a class="mention open" href="/i/TIK-1" title="Open work">TIK-1</a>"""), html)
    assert(html.contains("""<a class="mention closed" href="/i/TIK-2" title="Finished work">TIK-2</a>"""), html)

  test("a mention of an issue that does not exist yet is muted text, not a link"):
    val html = render("Waiting on TIK-99.")
    assert(html.contains("""<span class="mention missing">TIK-99</span>"""), html)
    assert(!html.contains("/i/TIK-99"), html)

  test("ids in code are never linked"):
    val html = render("`TIK-1` in code\n\n```\nTIK-2 in a block\n```")
    assert(!html.contains("href=\"/i/TIK-1\""), html)
    assert(!html.contains("href=\"/i/TIK-2\""), html)

  test("tokens that are not mentions stay text: unknown projects, joined characters, and the issue itself"):
    val html = render("UTF-8, xTIK-1, TIK-1s and TIK-1", self = Some(id("TIK-1")))
    assert(!html.contains("mention"), html)

  test("raw HTML is escaped, so agent-written markup shows as text"):
    val html = render("<script>alert('x')</script>")
    assert(!html.contains("<script>"), html)
    assert(html.contains("&lt;script&gt;"), html)
