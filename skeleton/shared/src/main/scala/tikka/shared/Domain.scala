package tikka.shared

import io.circe.{Codec as JsonCodec, Decoder, Encoder}
import sttp.tapir.{Codec, CodecFormat, Schema}

opaque type IssueId = String

object IssueId:
  private val pattern = "[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*".r

  def parse(raw: String): Either[String, IssueId] =
    if pattern.matches(raw) then Right(raw) else Left(s"not an issue id: $raw")

  def of(key: String, number: Long): IssueId = s"$key-$number"

  extension (id: IssueId) def value: String = id

  given Encoder[IssueId] = Encoder.encodeString
  given Decoder[IssueId] = Decoder.decodeString.emap(parse)
  given Schema[IssueId]  = Schema.string[IssueId].description("An issue id such as SKL-42")
  given Codec[String, IssueId, CodecFormat.TextPlain] =
    Codec.string.mapEither(parse)(_.value)

opaque type Title = String

object Title:
  def parse(raw: String): Either[String, Title] =
    if raw.trim.nonEmpty then Right(raw.trim) else Left("title must not be blank")

  extension (title: Title) def value: String = title

  given Encoder[Title] = Encoder.encodeString
  given Decoder[Title] = Decoder.decodeString.emap(parse)
  given Schema[Title]  = Schema.string[Title].description("A non-blank title")

final case class Issue(id: IssueId, title: Title) derives JsonCodec.AsObject, Schema

final case class CreateIssue(title: Title) derives JsonCodec.AsObject, Schema

final case class SearchResult(
    effective_query: String,
    issues: List[Issue],
    next_cursor: Option[String],
    has_more: Boolean,
) derives JsonCodec.AsObject, Schema

enum ErrorCode derives Schema:
  case not_found, invalid_argument, invalid_query

object ErrorCode:
  given Encoder[ErrorCode] = Encoder.encodeString.contramap(_.toString)
  given Decoder[ErrorCode] = Decoder.decodeString.emap(s =>
    ErrorCode.values.find(_.toString == s).toRight(s"unknown error code: $s")
  )

final case class ErrorBody(error: ErrorCode, message: String) derives JsonCodec.AsObject, Schema

// MCP tool arguments — the same shared types feed MCP inputSchema via tapir Schema.
final case class GetIssueArgs(id: IssueId) derives JsonCodec.AsObject, Schema

final case class SearchIssuesArgs(query: Option[String]) derives JsonCodec.AsObject, Schema
