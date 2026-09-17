package tikka.cli

import cats.effect.ExitCode
import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import sttp.client4.*
import sttp.model.Header
import sttp.model.Uri

/** `tikka mcp`: a stdio MCP server for clients that can only spawn one, forwarding to the daemon's HTTP endpoint.
  *
  * Framing only. It never interprets the protocol, so supporting a new revision is the daemon's work alone. The one
  * thing it adds is the HTTP headers the stateless revision requires to mirror the body, which stdio has no place for,
  * and the one thing it reads is a request's `id`, to answer with an error when the daemon has gone away.
  */
object McpProxy:
  def run(context: Context, write: String => IO[Unit]): IO[ExitCode] =
    // Checked once at launch, so a client with no daemon sees the server fail to start rather than hang.
    context.api.meta.flatMap:
      case Left(failure) => Output.failure(context.environment, json = false, failure)
      case Right(_)      =>
        context.environment.input
          .filter(_.trim.nonEmpty)
          .evalScan(Option.empty[String]): (session, line) =>
            forward(context, session, line).flatMap((next, reply) => reply.fold(IO.unit)(write).as(next))
          .compile
          .drain
          .as(ExitCode.Success)

  private def endpoint(context: Context): Uri =
    val query = context.settings.project.fold("")(key => s"?project=${key.value}")
    Uri.unsafeParse(s"${context.settings.baseUrl}/mcp$query")

  /** Sends one message and returns the session to use next, and the line to write back, if any. */
  private def forward(context: Context, session: Option[String], line: String): IO[(Option[String], Option[String])] =
    parse(line) match
      case Left(_)        => IO.pure((session, Some(error(Json.Null, -32700, "Parse error"))))
      case Right(message) =>
        val id = message.hcursor.downField("id").focus
        basicRequest
          .post(endpoint(context))
          .body(line)
          .contentType("application/json")
          .header("Accept", "application/json")
          .headers(mirrored(message, session)*)
          .response(asStringAlways)
          .send(context.backend)
          .attempt
          .map:
            case Left(problem) if Api.unreachable(problem) =>
              val reply = id.map(value =>
                error(
                  value,
                  -32000,
                  s"tikka daemon is not reachable at ${context.settings.baseUrl}; start it with `tikka daemon start`"
                )
              )
              (session, reply)
            case Left(problem)   => throw problem
            case Right(response) =>
              val next = response.header("Mcp-Session-Id").orElse(session)
              // A notification's 202 has no body, and a client must not see a line for it.
              val reply = Option.when(response.code.code != 202 && response.body.trim.nonEmpty)(oneLine(response.body))
              (next, reply)

  /** The stateless revision carries its version and method in both the body and the headers; stdio only has a body. */
  private def mirrored(message: Json, session: Option[String]): List[Header] =
    val cursor = message.hcursor
    val method = cursor.get[String]("method").toOption
    val version =
      cursor.downField("params").downField("_meta").get[String]("io.modelcontextprotocol/protocolVersion").toOption
    val name = cursor.downField("params").get[String]("name").toOption
    version match
      case Some(protocol) =>
        List(Header("MCP-Protocol-Version", protocol)) ++
          method.map(Header("Mcp-Method", _)).toList ++
          name.filter(_ => method.contains("tools/call")).map(Header("Mcp-Name", _)).toList
      case None => session.map(Header("Mcp-Session-Id", _)).toList

  private def oneLine(body: String): String = parse(body).fold(_ => body.replace("\n", " "), _.noSpaces)

  private def error(id: Json, code: Int, message: String): String =
    Json
      .obj(
        "jsonrpc" -> "2.0".asJson,
        "id" -> id,
        "error" -> Json.obj("code" -> code.asJson, "message" -> message.asJson)
      )
      .noSpaces
