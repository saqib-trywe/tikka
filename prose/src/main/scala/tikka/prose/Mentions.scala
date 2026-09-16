package tikka.prose

import laika.api.MarkupParser
import laika.ast.*
import laika.format.Markdown
import tikka.shared.IssueId
import tikka.shared.ProjectKey

/** Finds the issue ids a piece of prose refers to.
  *
  * Markdown-aware on purpose: the daemon counts mentions and the UI renders links from the same reading, so the UI can
  * never link something the daemon didn't count. Code never counts, which keeps pasted logs from creating backlinks.
  */
object Mentions:
  private val parser = MarkupParser.of(Markdown).build

  private val token = "[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*".r

  private val linkPath = "/i/([A-Z][A-Z0-9]{1,9}-[1-9][0-9]*)".r

  /** Every id the text mentions, given the projects that exist. The ids need not exist: a forward reference becomes a
    * live backlink when its issue is created.
    *
    * `self` is dropped, because an issue never mentions itself.
    */
  def detect(markdown: String, knownProjects: Set[ProjectKey], self: Option[IssueId]): Set[IssueId] =
    val found = for
      source <- prose(markdown)
      id <- idsIn(source)
      if knownProjects.contains(id.project) && !self.contains(id)
    yield id
    found.toSet

  /** The text a mention may live in: everything outside code, plus the paths of links into tikka's own issue pages. */
  private def prose(markdown: String): Seq[String] =
    parser.parse(markdown) match
      case Right(document) =>
        val text = document.content.collect:
          case Text(content, _) => content
        val links = document.content.collect:
          case link: SpanLink => destination(link.target)
        text ++ links
      case Left(_) =>
        // Markdown has no invalid input in practice; if Laika ever refuses, read the raw text rather than nothing.
        Seq(markdown)

  private def destination(target: Target): String = target match
    case ExternalTarget(url)      => url
    case internal: InternalTarget => internal.underlying.toString

  private def idsIn(source: String): Seq[IssueId] =
    val bare = token
      .findAllMatchIn(source)
      .filter(candidate => boundedLeft(source, candidate.start) && boundedRight(source, candidate.end))
      .map(_.matched)
    val linked = linkPath.findAllMatchIn(source).map(_.group(1))
    (bare ++ linked).flatMap(text => IssueId.parse(text).toOption).toSeq

  /** The characters either side of a token must not be letters, digits, `_` or `-`. */
  private def boundedLeft(source: String, start: Int): Boolean =
    start == 0 || isBoundary(source.charAt(start - 1))

  private def boundedRight(source: String, end: Int): Boolean =
    end == source.length || isBoundary(source.charAt(end))

  private def isBoundary(char: Char): Boolean =
    !(char.isLetterOrDigit || char == '_' || char == '-')
