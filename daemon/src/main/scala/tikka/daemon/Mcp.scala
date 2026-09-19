package tikka.daemon

import cats.data.EitherT
import cats.effect.IO
import cats.effect.Ref
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Header
import org.http4s.HttpRoutes
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.ci.*
import sttp.apispec.circe.*
import sttp.tapir.FieldName
import sttp.tapir.Schema
import sttp.tapir.SchemaType
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema
import tikka.core.Core
import tikka.shared.*
import tikka.shared.Wire.*

/** MCP, hand-rolled over http4s (ADR 0002): tools only, JSON responses only, nothing server-initiated.
  *
  * Two revisions are spoken, chosen per request: 2026-07-28, which is stateless with version and client in `_meta`, and
  * 2025-11-25, with its `initialize` handshake. A session exists only on the older revision, and only to remember the
  * client's name for the event actor.
  */
object Mcp:
  val modern: String = "2026-07-28"
  val legacy: String = "2025-11-25"

  val supportedVersions: List[String] = List(modern, legacy)

  private val metaVersion = "io.modelcontextprotocol/protocolVersion"
  private val metaCapabilities = "io.modelcontextprotocol/clientCapabilities"
  private val metaClient = "io.modelcontextprotocol/clientInfo"
  private val metaServer = "io.modelcontextprotocol/serverInfo"

  /** How long a client may cache the tool list. The list only changes when the daemon is upgraded. */
  private val cacheMillis = 60000

  private val serverInfo: Json =
    Json.obj("name" -> "tikka".asJson, "version" -> BuildVersion.current.value.asJson)

  /** Sessions on the older revision, holding only the client's name. In memory: a restarted daemon forgets them, and
    * clients re-initialise, as the revision expects.
    */
  final case class Sessions(names: Ref[IO, Map[String, String]])

  object Sessions:
    def make: IO[Sessions] = Ref.of[IO, Map[String, String]](Map.empty).map(Sessions.apply)

  def routes(core: Core, sessions: Sessions): HttpRoutes[IO] = HttpRoutes.of[IO]:
    case GET -> Root / "mcp"            => IO.pure(Response[IO](Status.MethodNotAllowed))
    case DELETE -> Root / "mcp"         => IO.pure(Response[IO](Status.MethodNotAllowed))
    case request @ POST -> Root / "mcp" =>
      binding(core, request.params.get("project")).flatMap:
        case Left(refusal)  => IO.pure(refusal)
        case Right(project) =>
          request
            .as[Json]
            .attempt
            .flatMap:
              case Left(_)     => respond(Status.BadRequest, rpcError(None, -32700, "Parse error"))
              case Right(body) => handle(core, sessions, request, body, project)

  /** The endpoint URL binds a connection to a project. An unknown key is refused at the HTTP level: the model cannot
    * fix its own config, but the human who wrote it sees a connection failure naming the valid keys.
    */
  private def binding(core: Core, requested: Option[String]): IO[Either[Response[IO], Option[ProjectKey]]] =
    requested match
      case None       => IO.pure(Right(None))
      case Some(text) =>
        core.projects.map: projects =>
          val keys = projects.map(_.key)
          ProjectKey.parse(text).toOption.filter(key => keys.exists(_.value == key.value)) match
            case Some(key) => Right(Some(key))
            case None      =>
              val error = Rejection.of(
                DomainError.UnknownProject(ProjectKey.parse(text).getOrElse(ProjectKey.trusted(text)), keys)
              )
              Left(Response[IO](Status.NotFound).withEntity(error.asJson))

  private def handle(
      core: Core,
      sessions: Sessions,
      request: Request[IO],
      body: Json,
      project: Option[ProjectKey]
  ): IO[Response[IO]] =
    val cursor = body.hcursor
    val id = cursor.downField("id").focus.filterNot(_.isNull)
    val method = cursor.downField("method").as[String].toOption
    val params = cursor.downField("params").focus.getOrElse(Json.obj())
    val meta = params.hcursor.downField("_meta").focus
    def header(name: String): Option[String] = request.headers.get(CIString(name)).map(_.head.value)
    val modernRequest = meta.isDefined || header("MCP-Protocol-Version").contains(modern)
    (id, method) match
      case (_, None) => respond(Status.BadRequest, rpcError(id, -32600, "Invalid Request"))
      // A notification expects no answer.
      case (None, Some(_))                                => IO.pure(Response[IO](Status.Accepted))
      case (Some(requestId), Some(name)) if modernRequest =>
        modernCall(core, requestId, name, params, meta.getOrElse(Json.obj()), header, project)
      case (Some(requestId), Some("initialize")) => initialize(sessions, requestId, params)
      case (Some(requestId), Some(name))         =>
        header("Mcp-Session-Id") match
          case None =>
            respond(
              Status.BadRequest,
              rpcError(Some(requestId), -32602, s"Invalid params: _meta.$metaVersion is required, or initialize first")
            )
          case Some(session) =>
            sessions.names.get
              .map(_.get(session))
              .flatMap:
                case None =>
                  respond(Status.NotFound, rpcError(Some(requestId), -32600, "Unknown session; initialize again"))
                case Some(client) =>
                  dispatch(core, requestId, name, params, Actor(Surface.Mcp, Some(client)), project, isModern = false)

  private def initialize(sessions: Sessions, id: Json, params: Json): IO[Response[IO]] =
    val client = params.hcursor.downField("clientInfo").downField("name").as[String].getOrElse("unknown")
    val session = java.util.UUID.randomUUID().toString
    sessions.names.update(_ + (session -> client)) *>
      respond(
        Status.Ok,
        success(
          id,
          Json.obj(
            "protocolVersion" -> legacy.asJson,
            "capabilities" -> Json.obj("tools" -> Json.obj()),
            "serverInfo" -> serverInfo
          )
        ),
        Header.Raw(ci"Mcp-Session-Id", session)
      )

  /** The stateless revision's checks, in the order its conformance scenarios expect. */
  private def modernCall(
      core: Core,
      id: Json,
      method: String,
      params: Json,
      meta: Json,
      header: String => Option[String],
      project: Option[ProjectKey]
  ): IO[Response[IO]] =
    val version = meta.hcursor.downField(metaVersion).as[String].toOption
    val capabilities = meta.hcursor.downField(metaCapabilities).focus
    val headerVersion = header("MCP-Protocol-Version")
    if version.isEmpty || capabilities.isEmpty then
      respond(
        Status.BadRequest,
        rpcError(Some(id), -32602, s"Invalid params: _meta.$metaVersion and _meta.$metaCapabilities are required")
      )
    else if headerVersion != version then
      respond(
        Status.BadRequest,
        rpcError(
          Some(id),
          -32020,
          s"Header mismatch: MCP-Protocol-Version ${headerVersion.getOrElse("<missing>")} vs body ${version.getOrElse("")}"
        )
      )
    else if !version.contains(modern) then
      respond(
        Status.BadRequest,
        rpcError(
          Some(id),
          -32022,
          "Unsupported protocol version",
          Some(Json.obj("supported" -> supportedVersions.asJson, "requested" -> version.asJson))
        )
      )
    else if !header("Mcp-Method").contains(method) then
      respond(
        Status.BadRequest,
        rpcError(
          Some(id),
          -32020,
          s"Header mismatch: Mcp-Method ${header("Mcp-Method").getOrElse("<missing>")} vs body $method"
        )
      )
    else if method == "tools/call" && header("Mcp-Name") != params.hcursor.downField("name").as[String].toOption then
      respond(Status.BadRequest, rpcError(Some(id), -32020, "Header mismatch: Mcp-Name does not match params.name"))
    else
      val client = meta.hcursor.downField(metaClient).downField("name").as[String].toOption
      dispatch(core, id, method, params, Actor(Surface.Mcp, client.orElse(Some("unknown"))), project, isModern = true)

  private def dispatch(
      core: Core,
      id: Json,
      method: String,
      params: Json,
      actor: Actor,
      project: Option[ProjectKey],
      isModern: Boolean
  ): IO[Response[IO]] =
    def complete(result: Json): Json =
      if isModern then
        result.deepMerge(Json.obj("resultType" -> "complete".asJson, "_meta" -> Json.obj(metaServer -> serverInfo)))
      else result
    def cached(result: Json): Json =
      if isModern then result.deepMerge(Json.obj("ttlMs" -> cacheMillis.asJson, "cacheScope" -> "public".asJson))
      else result
    method match
      case "ping" if !isModern           => respond(Status.Ok, success(id, Json.obj()))
      case "server/discover" if isModern =>
        respond(
          Status.Ok,
          success(
            id,
            complete(
              cached(
                Json.obj(
                  "supportedVersions" -> supportedVersions.asJson,
                  "capabilities" -> Json.obj("tools" -> Json.obj()),
                  "serverInfo" -> serverInfo
                )
              )
            )
          )
        )
      case "tools/list" => respond(Status.Ok, success(id, complete(cached(Json.obj("tools" -> toolList)))))
      case "tools/call" =>
        val name = params.hcursor.downField("name").as[String].getOrElse("")
        val arguments = params.hcursor.downField("arguments").focus.getOrElse(Json.obj())
        tools.find(_.name == name) match
          case None       => respond(Status.Ok, rpcError(Some(id), -32602, s"Unknown tool: $name"))
          case Some(tool) =>
            tool
              .call(core, arguments, actor, project)
              .flatMap(result => respond(Status.Ok, success(id, complete(result))))
      // The stateless revision removed ping and initialize, so asking for them there is asking for nothing.
      case other =>
        respond(
          if isModern then Status.NotFound else Status.Ok,
          rpcError(Some(id), -32601, s"Method not found: $other")
        )

  // Tools

  /** A tool: its declared schemas, and a handler that returns a finished result. */
  final case class Tool(
      name: String,
      description: String,
      inputSchema: Json,
      outputSchema: Json,
      call: (Core, Json, Actor, Option[ProjectKey]) => IO[Json]
  )

  private def schemaJson[A](schema: Schema[A]): Json =
    TapirSchemaToJsonSchema(schema, markOptionsAsNullable = false).asJson.deepDropNullValues

  /** Tools that act on one issue take its `id` beside the same body the HTTP route takes. */
  private def withId[A](schema: Schema[A]): Schema[A] = schema.schemaType match
    case product: SchemaType.SProduct[A] =>
      val id = SchemaType.SProductField[A, String](
        FieldName("id"),
        Schema.schemaForString.description("The issue's id, such as TIK-42."),
        _ => None
      )
      schema.copy(schemaType = product.copy(fields = id :: product.fields))
    case _ => schema

  private def tool[In: Decoder, Out: Encoder](
      name: String,
      description: String,
      input: Schema[In],
      output: Schema[Out],
      render: Out => String
  )(handle: (Core, In, Json, Actor, Option[ProjectKey]) => EitherT[IO, DomainError, Out]): Tool =
    Tool(
      name,
      description,
      schemaJson(input),
      schemaJson(output),
      (core, arguments, actor, project) =>
        arguments.as[In] match
          case Left(failure) =>
            IO.pure(rejected(DomainError.InvalidArgument("arguments", failure.getMessage)))
          case Right(decoded) =>
            handle(core, decoded, arguments, actor, project).value.map:
              case Left(error)  => rejected(error)
              case Right(value) => finished(value, render)
    )

  private def issueTool[In: Decoder, Out: Encoder](
      name: String,
      description: String,
      input: Schema[In],
      output: Schema[Out],
      render: Out => String
  )(handle: (Core, String, In, Actor) => EitherT[IO, DomainError, Out]): Tool =
    tool(name, description, withId(input), output, render): (core, decoded, arguments, actor, _) =>
      for
        text <- EitherT.fromEither[IO](
          arguments.hcursor
            .get[String]("id")
            .left
            .map(_ => DomainError.InvalidArgument("id", "an issue id is required"))
        )
        output <- handle(core, text, decoded, actor)
      yield output

  /** Every result carries text for the model and `structuredContent` against the declared schema. Nulls are dropped,
    * because a client validates that content and an absent optional is not the same as a null one.
    */
  private def finished[Out: Encoder](value: Out, render: Out => String): Json =
    Json.obj(
      "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> render(value).asJson)),
      "structuredContent" -> value.asJson.deepDropNullValues,
      "isError" -> false.asJson
    )

  /** A domain rejection is a tool result the model can read and act on, never a protocol error it may never see. */
  private def rejected(error: DomainError): Json =
    val body = Rejection.of(error)
    Json.obj(
      "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> Render.error(body).asJson)),
      "structuredContent" -> body.asJson.deepDropNullValues,
      "isError" -> true.asJson
    )

  private def lift[A](value: Either[DomainError, A]): EitherT[IO, DomainError, A] = Operations.lift(value)

  val tools: List[Tool] = List(
    tool(
      "search_issues",
      """Start here to find work: `ready` lists open, unblocked, unclaimed issues. Rows never include bodies; use get_issue for one issue.
        |Query grammar: filters are ANDed, commas OR values within a filter, a leading - negates a filter.
        |Filters: project:KEY|* status:open|closed resolution:done|dropped assignee:NAME|none|any|prefix/* label:NAME|prefix:*
        |parent:ID under:ID blocks:ID blocked-by:ID mentions:ID mentioned-by:ID id:ID,ID text:WORDS (substring, quote spaces)
        |blocked unblocked ready created-/updated-/closed-/claimed-before|after:3d|2h|30m|2026-09-16 sort:rank|created|updated|closed[-asc|-desc]
        |Example: ready label:bug -assignee:none. Page with next_cursor; issues that did not change while you page appear exactly once.""".stripMargin,
      summon[Schema[SearchIn]],
      summon[Schema[SearchOut]],
      Render.search
    ): (core, in, _, _, project) =>
      Operations.search(core, in.query.getOrElse(""), project, in.cursor, in.limit.getOrElse(Core.defaultLimit)),
    tool(
      "get_issue",
      "Fetch one issue: its fields, body, version, edges (parent, children, blockers, blocking, mentions, backlinks) and comments. Pass include: [\"events\"] for its latest 50 events.",
      summon[Schema[GetIn]],
      summon[Schema[IssueOut]],
      Render.issue
    ): (core, in, _, _, _) =>
      for
        events <- lift(in.include.getOrElse(Nil) match
          case Nil            => Right(false)
          case List("events") => Right(true)
          case other          =>
            Left(DomainError.InvalidArgument("include", s"${other.mkString(", ")}: only events can be included")))
        view <- Operations.get(core, in.id, events)
      yield view,
    tool(
      "create_issue",
      "Create an issue. Not idempotent: if a create may have failed, search before retrying. Unbound connections must name the project. Give at most one of rank, rank_before and rank_after.",
      summon[Schema[CreateIn]],
      summon[Schema[WrittenOut]],
      Render.written
    ): (core, in, _, actor, project) =>
      Operations.create(core, in, project, actor),
    issueTool(
      "update_issue",
      "Change an issue's title, body, labels, parent, blockers or rank, or just add a comment. body replaces the whole body; body_edits applies exact, unique {old, replacement} edits in order, all or nothing. Pass expected_version to refuse the write if the title, body or labels changed since you read them. parent: null clears the parent. The assignee is not changed here: use claim_issue, release_issue or reassign_issue.",
      summon[Schema[UpdateIn]],
      summon[Schema[WrittenOut]],
      Render.written
    )(Operations.update),
    issueTool(
      "claim_issue",
      "Take an unclaimed issue before working on it. The assignee must name your session, not just you (for example saqib/wf-7f3a): claiming what you already hold succeeds, so two sessions sharing a name would both think they won. Fails with claim_conflict if someone else holds it.",
      summon[Schema[ClaimIn]],
      summon[Schema[ClaimedOut]],
      Render.claimed
    )(Operations.claim),
    issueTool(
      "release_issue",
      "Give back an issue you hold, for example when you are blocked. Name yourself as the assignee, exactly as you claimed it.",
      summon[Schema[ClaimIn]],
      summon[Schema[RowOnly]],
      (out: RowOnly) => Render.row(out.row)
    )(Operations.release),
    issueTool(
      "reassign_issue",
      "Move an issue held by someone else, naming who you expect holds it now. Use none for nobody: reassigning to none clears a stale claim left by a session that ended.",
      summon[Schema[ReassignIn]],
      summon[Schema[RowOnly]],
      (out: RowOnly) => Render.row(out.row)
    )(Operations.reassign),
    issueTool(
      "close_issue",
      "Close an issue as done (the work was carried out) or dropped (abandoned, obsolete or out of scope), with a comment saying why. Refused while it has open children, or as done while a blocker is open. Returns the issues this close unblocked.",
      summon[Schema[CloseIn]],
      summon[Schema[ClosedOutWire]],
      Render.closed
    )(Operations.close),
    issueTool(
      "reopen_issue",
      "Reopen a closed issue, with a comment saying why. Refused while its parent is closed, or while an issue it blocks is closed done.",
      summon[Schema[ReopenIn]],
      summon[Schema[WrittenOut]],
      Render.written
    )(Operations.reopen)
  )

  private val toolList: Json = tools
    .map: tool =>
      Json.obj(
        "name" -> tool.name.asJson,
        "description" -> tool.description.asJson,
        "inputSchema" -> tool.inputSchema,
        "outputSchema" -> tool.outputSchema
      )
    .asJson

  // JSON-RPC

  private def success(id: Json, result: Json): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> result)

  private def rpcError(id: Option[Json], code: Int, message: String, data: Option[Json] = None): Json =
    Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> id.getOrElse(Json.Null),
      "error" -> Json
        .obj("code" -> code.asJson, "message" -> message.asJson)
        .deepMerge(data.fold(Json.obj())(value => Json.obj("data" -> value)))
    )

  private def respond(status: Status, body: Json, headers: Header.ToRaw*): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body).putHeaders(headers*))
