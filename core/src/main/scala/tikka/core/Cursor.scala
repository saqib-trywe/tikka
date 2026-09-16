package tikka.core

import tikka.shared.DomainError
import tikka.shared.Sort

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** The opaque string a caller pages with.
  *
  * It carries the sort it was made for: paging with a cursor from a differently sorted query would silently walk the
  * wrong order, so it is refused instead.
  */
private[core] object Cursor:
  private val version = "1"

  def encode(sort: Sort, keyset: Search.Keyset): String =
    val payload = List(
      version,
      sort.field.toString,
      sort.direction.toString,
      if keyset.missing then "1" else "0",
      keyset.value,
      keyset.project,
      keyset.number.toString
    ).mkString("|")
    Base64.getUrlEncoder.withoutPadding.encodeToString(payload.getBytes(UTF_8))

  def decode(text: String, sort: Sort): Either[DomainError, Search.Keyset] =
    val decoded =
      try Some(String(Base64.getUrlDecoder.decode(text), UTF_8))
      catch case _: IllegalArgumentException => None
    decoded.map(_.split("\\|", -1).toList) match
      case Some(List(`version`, field, direction, missing, value, project, number))
          if field == sort.field.toString && direction == sort.direction.toString =>
        number.toIntOption
          .map(Search.Keyset(missing == "1", value, project, _))
          .toRight(refused("this cursor is damaged"))
      case Some(List(`version`, _, _, _, _, _, _)) =>
        Left(refused("this cursor belongs to a differently sorted query"))
      case _ => Left(refused("this cursor is not one tikka issued"))

  private def refused(reason: String): DomainError = DomainError.InvalidArgument("cursor", reason)
