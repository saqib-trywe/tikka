package tikka.daemon

import cats.effect.{IO, Ref}
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax.*
import org.http4s.{Header, HttpRoutes, Response, Status}
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.ci.*
import sttp.apispec.circe.*
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema
import tikka.shared.*

/** Hand-rolled MCP over HTTP (ADR 0002): dual-era, JSON responses only. */
object Mcp:
  val Modern = "2026-07-28"
  val Legacy = "2025-11-25"
  private val MetaVersion = "io.modelcontextprotocol/protocolVersion"
  private val MetaCaps    = "io.modelcontextprotocol/clientCapabilities"
  private val MetaClient  = "io.modelcontextprotocol/clientInfo"
  private val serverInfo  = Json.obj("name" -> "tikka".asJson, "version" -> Home.version.asJson)

  private def schemaJson[A](using s: Schema[A]): Json =
    TapirSchemaToJsonSchema(s, markOptionsAsNullable = false).asJson.deepDropNullValues

  private final case class Tool(name: String, description: String, input: Json, output: Json)

  private val tools = List(
    Tool("get_issue", "Fetch one issue by id, e.g. SKL-1.", schemaJson[GetIssueArgs], schemaJson[Issue]),
    Tool("search_issues", "Start here to find work. The skeleton accepts only an empty query.",
      schemaJson[SearchIssuesArgs], schemaJson[SearchResult]),
  )

  private def toolsJson: Json = Json.obj("tools" -> tools.map(t => Json.obj(
    "name" -> t.name.asJson, "description" -> t.description.asJson,
    "inputSchema" -> t.input, "outputSchema" -> t.output)).asJson)

  // ---- JSON-RPC envelopes ----------------------------------------------------------------------
  private def ok(id: Json, result: Json): Json = Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> result)
  private def rpcError(id: Option[Json], code: Int, message: String, data: Option[Json] = None): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id.getOrElse(Json.Null),
      "error" -> Json.obj("code" -> code.asJson, "message" -> message.asJson).deepMerge(
        data.fold(Json.obj())(d => Json.obj("data" -> d))))

  private def json(status: Status, body: Json, headers: Header.ToRaw*): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body).putHeaders(headers*))

  // ---- tool calls: domain rejections are isError results, never protocol errors ------------------
  private def result[A: io.circe.Encoder](value: Either[ErrorBody, A], text: A => String): Json = value match
    case Right(a)  => Json.obj("content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> text(a).asJson)),
                        "structuredContent" -> a.asJson.deepDropNullValues, "isError" -> false.asJson)
    case Left(err) => Json.obj("content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> s"${err.error}: ${err.message}".asJson)),
                        "structuredContent" -> err.asJson, "isError" -> true.asJson)

  private def decodeArgs[A: Decoder](args: Json): Either[ErrorBody, A] =
    args.as[A].left.map(f => ErrorBody(ErrorCode.invalid_argument, f.getMessage))

  private def callTool(core: Core, name: String, args: Json, actor: String): IO[Either[String, Json]] =
    IO.println(s"mcp tools/call $name by $actor") >> (name match
      case "get_issue" => decodeArgs[GetIssueArgs](args) match
          case Left(e)  => IO.pure(Right(result[Issue](Left(e), Render.row)))
          case Right(a) => core.get(a.id).map(r => Right(result(r, Render.row)))
      case "search_issues" => decodeArgs[SearchIssuesArgs](args) match
          case Left(e)  => IO.pure(Right(result[SearchResult](Left(e), Render.rows)))
          case Right(a) => core.search(a.query).map(r => Right(result(r, Render.rows)))
      case other => IO.pure(Left(s"Unknown tool: $other")))

  // ---- routes ----------------------------------------------------------------------------------
  def routes(core: Core, sessions: Ref[IO, Map[String, String]]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "mcp"    => IO.pure(Response[IO](Status.MethodNotAllowed))
    case DELETE -> Root / "mcp" => IO.pure(Response[IO](Status.MethodNotAllowed))
    case req @ POST -> Root / "mcp" =>
      req.params.get("project") match
        case Some(key) if key != "SKL" =>
          json(Status.NotFound, Json.obj("error" -> "unknown_project".asJson,
            "message" -> s"no project $key; valid keys: SKL".asJson, "valid_keys" -> List("SKL").asJson))
        case _ =>
          req.as[Json].attempt.flatMap {
            case Left(_) => json(Status.BadRequest, rpcError(None, -32700, "Parse error"))
            case Right(body) => handle(core, sessions, req, body)
          }
  }

  private def handle(core: Core, sessions: Ref[IO, Map[String, String]], req: org.http4s.Request[IO], body: Json): IO[Response[IO]] =
    val c       = body.hcursor
    val idOpt   = c.downField("id").focus.filterNot(_.isNull)
    val method  = c.downField("method").as[String].toOption
    val params  = c.downField("params").focus.getOrElse(Json.obj())
    val meta    = params.hcursor.downField("_meta")
    val version = meta.downField(MetaVersion).as[String].toOption
    def header(name: String) = req.headers.get(CIString(name)).map(_.head.value)

    (idOpt, method) match
      case (_, None) => json(Status.BadRequest, rpcError(idOpt, -32600, "Invalid Request"))
      case (None, Some(_)) => IO.pure(Response[IO](Status.Accepted)) // notification
      case (Some(id), Some(m)) if meta.focus.isDefined || header("MCP-Protocol-Version").contains(Modern) =>
        modern(core, id, m, params, version, meta, header)
      case (Some(id), Some("initialize")) =>
        // Legacy era: session exists only to remember the client name for the event actor.
        val requested = params.hcursor.downField("protocolVersion").as[String].getOrElse(Legacy)
        val client    = params.hcursor.downField("clientInfo").downField("name").as[String].getOrElse("unknown")
        val sid       = java.util.UUID.randomUUID().toString
        sessions.update(_ + (sid -> client)) >>
          json(Status.Ok, ok(id, Json.obj(
            "protocolVersion" -> (if requested == Legacy then Legacy else Legacy).asJson,
            "capabilities" -> Json.obj("tools" -> Json.obj()),
            "serverInfo" -> serverInfo)), Header.Raw(ci"Mcp-Session-Id", sid))
      case (Some(id), Some(m)) =>
        header("Mcp-Session-Id") match
          case None => json(Status.BadRequest, rpcError(Some(id), -32602, s"Invalid params: _meta.$MetaVersion is required (or initialize first for 2025-11-25)"))
          case Some(sid) => sessions.get.map(_.get(sid)).flatMap {
              case None => json(Status.NotFound, rpcError(Some(id), -32600, "Unknown session; re-initialize"))
              case Some(client) => dispatch(core, id, m, params, s"mcp:$client", modern = false)
            }

  private def modern(core: Core, id: Json, m: String, params: Json, versionOpt: Option[String],
                     meta: io.circe.ACursor, header: String => Option[String]): IO[Response[IO]] =
    val headerVersion = header("MCP-Protocol-Version")
    val version = versionOpt.getOrElse("")
    if versionOpt.isEmpty || meta.downField(MetaCaps).focus.isEmpty then
      json(Status.BadRequest, rpcError(Some(id), -32602, s"Invalid params: _meta.$MetaVersion and _meta.$MetaCaps are required"))
    else if !headerVersion.contains(version) then
      json(Status.BadRequest, rpcError(Some(id), -32020, s"Header mismatch: MCP-Protocol-Version ${headerVersion.getOrElse("<missing>")} vs body $version"))
    else if version != Modern then
      json(Status.BadRequest, rpcError(Some(id), -32022, "Unsupported protocol version",
        Some(Json.obj("supported" -> List(Modern, Legacy).asJson, "requested" -> version.asJson))))
    else if !header("Mcp-Method").contains(m) then
      json(Status.BadRequest, rpcError(Some(id), -32020, s"Header mismatch: Mcp-Method ${header("Mcp-Method").getOrElse("<missing>")} vs body $m"))
    else if m == "tools/call" && header("Mcp-Name") != params.hcursor.downField("name").as[String].toOption then
      json(Status.BadRequest, rpcError(Some(id), -32020, "Header mismatch: Mcp-Name does not match params.name"))
    else
      val client = meta.downField(MetaClient).downField("name").as[String].getOrElse("unknown")
      dispatch(core, id, m, params, s"mcp:$client", modern = true)

  private def dispatch(core: Core, id: Json, m: String, params: Json, actor: String, modern: Boolean): IO[Response[IO]] =
    def complete(result: Json): Json =
      if modern then result.deepMerge(Json.obj("resultType" -> "complete".asJson,
        "_meta" -> Json.obj("io.modelcontextprotocol/serverInfo" -> serverInfo)))
      else result
    m match
      case "ping" if !modern => json(Status.Ok, ok(id, Json.obj()))
      case "server/discover" if modern =>
        json(Status.Ok, ok(id, complete(Json.obj(
          "supportedVersions" -> List(Modern, Legacy).asJson,
          "capabilities" -> Json.obj("tools" -> Json.obj()),
          "ttlMs" -> 60000.asJson, "cacheScope" -> "public".asJson))))
      case "tools/list" =>
        val cached = if modern then toolsJson.deepMerge(Json.obj("ttlMs" -> 60000.asJson, "cacheScope" -> "public".asJson)) else toolsJson
        json(Status.Ok, ok(id, complete(cached)))
      case "tools/call" =>
        val name = params.hcursor.downField("name").as[String].getOrElse("")
        val args = params.hcursor.downField("arguments").focus.getOrElse(Json.obj())
        callTool(core, name, args, s"$actor (${if modern then Modern else Legacy})").flatMap {
          case Right(r)  => json(Status.Ok, ok(id, complete(r)))
          case Left(msg) => json(Status.Ok, rpcError(Some(id), -32602, msg))
        }
      case other =>
        json(if modern then Status.NotFound else Status.Ok, rpcError(Some(id), -32601, s"Method not found: $other"))
