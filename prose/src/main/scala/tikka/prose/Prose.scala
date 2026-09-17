package tikka.prose

import laika.api.Transformer
import laika.ast.*
import laika.format.HTML
import laika.format.Markdown
import tikka.shared.IssueId
import tikka.shared.ProjectKey

/** What a rendered mention shows: the target's title for the tooltip, and whether it is closed. */
final case class MentionTarget(title: String, closed: Boolean)

/** Markdown to HTML for the web UI, with mentions as links.
  *
  * Built on the same Laika reading and the same token rule as [[Mentions]], so a link appears exactly where the daemon
  * counted a mention, and never inside code. Raw HTML stays off, which is Laika's default: an agent-written `<script>`
  * renders as text.
  */
object Prose:
  /** Renders `markdown`. `lookup` answers for issues that exist; a mention of one that does not yet is shown muted. */
  def html(
      markdown: String,
      knownProjects: Set[ProjectKey],
      self: Option[IssueId],
      lookup: IssueId => Option[MentionTarget]
  ): String =
    val transformer = Transformer
      .from(Markdown)
      .to(HTML)
      .usingSpanRule:
        case text: Text =>
          val found = Mentions
            .tokens(text.content)
            .filter(token => knownProjects.contains(token.id.project) && !self.contains(token.id))
          if found.isEmpty then RewriteAction.Retain
          else RewriteAction.Replace(SpanSequence(split(text, found, lookup)))
      .build
    transformer.transform(markdown).getOrElse(escape(markdown))

  private def split(text: Text, found: List[Mentions.Token], lookup: IssueId => Option[MentionTarget]): Seq[Span] =
    val content = text.content
    val pieces = List.newBuilder[Span]
    val last = found.foldLeft(0): (from, token) =>
      if token.start > from then pieces += Text(content.substring(from, token.start), text.options)
      pieces += mention(token.id, lookup(token.id))
      token.end
    if last < content.length then pieces += Text(content.substring(last), text.options)
    pieces.result()

  private def mention(id: IssueId, target: Option[MentionTarget]): Span = target match
    case Some(found) =>
      val state = if found.closed then "closed" else "open"
      SpanLink(Seq(Text(id.render)), ExternalTarget(s"/i/${id.render}"), Some(found.title), Styles("mention", state))
    // Numbers are never reused, so this can only ever mean one issue; it becomes a link once that issue exists.
    case None => Text(id.render, Styles("mention", "missing"))

  private def escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
