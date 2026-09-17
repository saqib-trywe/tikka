package tikka.daemon

import cats.effect.IO
import cats.syntax.all.*
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Method
import tools.jackson.databind.json.JsonMapper

import scala.jdk.CollectionConverters.*

/** MCP as a client sees it: both revisions, every tool, and every result checked against the schema tikka declared. */
class McpTest extends RunningDaemon:
  private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
  private val mapper = JsonMapper.builder().build()

  /** A request on the stateless revision, with the metadata and mirrored headers it requires. */
  private def modern(
      running: Live,
      method: String,
      params: Json = Json.obj(),
      project: Option[String] = None
  ): IO[(Int, Json)] =
    val meta = Json.obj(
      "io.modelcontextprotocol/protocolVersion" -> Mcp.modern.asJson,
      "io.modelcontextprotocol/clientCapabilities" -> Json.obj(),
      "io.modelcontextprotocol/clientInfo" -> Json.obj("name" -> "test-client".asJson, "version" -> "1".asJson)
    )
    val body = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> 1.asJson,
      "method" -> method.asJson,
      "params" -> params.deepMerge(Json.obj("_meta" -> meta))
    )
    val name = params.hcursor.get[String]("name").toOption.map("Mcp-Name" -> _).toList
    val path = project.fold("/mcp")(key => s"/mcp?project=$key")
    running.send(
      Method.POST,
      path,
      Some(body),
      List("MCP-Protocol-Version" -> Mcp.modern, "Mcp-Method" -> method) ++ name
    )

  private def callTool(running: Live, name: String, arguments: Json, project: Option[String] = None): IO[Json] =
    modern(running, "tools/call", Json.obj("name" -> name.asJson, "arguments" -> arguments), project)
      .map((_, body) => body.hcursor.downField("result").focus.getOrElse(fail(s"no result from $name: $body")))

  private def tools(running: Live): IO[Map[String, Json]] =
    modern(running, "tools/list").map: (_, body) =>
      body.hcursor
        .downField("result")
        .downField("tools")
        .as[List[Json]]
        .getOrElse(Nil)
        .flatMap(tool => tool.hcursor.get[String]("name").toOption.map(_ -> tool))
        .toMap

  /** The check Claude Code performs: structured content must satisfy the tool's own output schema. */
  private def conforms(declared: Map[String, Json], name: String, result: Json): Unit =
    val schema =
      declared(name).hcursor.downField("outputSchema").focus.getOrElse(fail(s"$name declares no output schema"))
    val content =
      result.hcursor.downField("structuredContent").focus.getOrElse(fail(s"$name returned no structured content"))
    val errors = registry.getSchema(schema.noSpaces).validate(mapper.readTree(content.noSpaces)).asScala.map(_.toString)
    assert(errors.isEmpty, s"$name's result breaks its own schema: ${errors.mkString("; ")}\n$content")
    assert(!content.noSpaces.contains(":null"), s"$name's structured content carries nulls: $content")

  private def project(running: Live, key: String): IO[Unit] =
    running.send(Method.POST, "/api/projects", Some(Json.obj("key" -> key.asJson, "name" -> key.asJson))).void

  home.test("the stateless revision lists nine tools with schemas and caching hints"): value =>
    live(value): running =>
      for
        (status, body) <- modern(running, "tools/list")
        discover <- modern(running, "server/discover")
      yield
        val result = body.hcursor.downField("result")
        assertEquals(status, 200)
        assertEquals(
          result.downField("tools").as[List[Json]].map(_.flatMap(_.hcursor.get[String]("name").toOption)),
          Right(
            List(
              "search_issues",
              "get_issue",
              "create_issue",
              "update_issue",
              "claim_issue",
              "release_issue",
              "reassign_issue",
              "close_issue",
              "reopen_issue"
            )
          )
        )
        assert(result.downField("ttlMs").as[Int].isRight)
        assertEquals(result.get[String]("cacheScope"), Right("public"))
        assertEquals(result.get[String]("resultType"), Right("complete"))
        assertEquals(
          discover._2.hcursor.downField("result").get[List[String]]("supportedVersions"),
          Right(Mcp.supportedVersions)
        )

  home.test("every tool's result satisfies the output schema it declares"): value =>
    live(value): running =>
      for
        _ <- project(running, "TIK")
        declared <- tools(running)
        results <- List(
          "create_issue" -> Json.obj("title" -> "The parent".asJson, "project" -> "TIK".asJson),
          "create_issue" -> Json.obj("title" -> "A step".asJson, "project" -> "TIK".asJson, "parent" -> "TIK-1".asJson),
          "update_issue" -> Json.obj(
            "id" -> "TIK-2".asJson,
            "labels_add" -> List("bug").asJson,
            "comment" -> "tagged".asJson
          ),
          "claim_issue" -> Json.obj("id" -> "TIK-2".asJson, "assignee" -> "saqib/wf-1".asJson),
          "release_issue" -> Json.obj("id" -> "TIK-2".asJson, "assignee" -> "saqib/wf-1".asJson),
          "claim_issue" -> Json.obj("id" -> "TIK-2".asJson, "assignee" -> "saqib/wf-2".asJson),
          "reassign_issue" -> Json.obj("id" -> "TIK-2".asJson, "from" -> "saqib/wf-2".asJson, "to" -> "none".asJson),
          "close_issue" -> Json.obj(
            "id" -> "TIK-2".asJson,
            "resolution" -> "done".asJson,
            "comment" -> "finished".asJson
          ),
          "reopen_issue" -> Json.obj("id" -> "TIK-2".asJson, "comment" -> "more to do".asJson),
          "get_issue" -> Json.obj("id" -> "TIK-2".asJson, "include" -> List("events").asJson),
          "search_issues" -> Json.obj("query" -> "project:TIK".asJson, "limit" -> 1.asJson)
        ).traverse((name, arguments) => callTool(running, name, arguments).map(name -> _))
      yield results.foreach: (name, result) =>
        assertEquals(result.hcursor.get[Boolean]("isError"), Right(false), s"$name failed: $result")
        conforms(declared, name, result)

  home.test("the schema check is not vacuous: a result that breaks a declared schema is caught"): value =>
    live(value): running =>
      for declared <- tools(running)
      yield
        val broken =
          Json.obj("structuredContent" -> Json.obj("row" -> Json.obj("id" -> 42.asJson), "version" -> "one".asJson))
        val caught = scala.util.Try(conforms(declared, "update_issue", broken))
        assert(caught.isFailure, "a wrong-typed result must fail the schema check")

  home.test("a domain rejection is a readable tool result carrying the contract's error, not a protocol error"):
    value =>
      live(value): running =>
        for
          _ <- project(running, "TIK")
          _ <- callTool(running, "create_issue", Json.obj("title" -> "Contended".asJson, "project" -> "TIK".asJson))
          _ <- callTool(running, "claim_issue", Json.obj("id" -> "TIK-1".asJson, "assignee" -> "first".asJson))
          rejected <- callTool(running, "claim_issue", Json.obj("id" -> "TIK-1".asJson, "assignee" -> "second".asJson))
        yield
          val cursor = rejected.hcursor
          assertEquals(cursor.get[Boolean]("isError"), Right(true))
          assertEquals(cursor.downField("structuredContent").get[String]("error"), Right("claim_conflict"))
          assertEquals(cursor.downField("structuredContent").get[String]("holder"), Right("first"))
          assert(
            cursor.downField("content").downArray.get[String]("text").exists(_.contains("reassign_issue")),
            "the text tells the model what to do instead"
          )

  home.test("a bound connection creates in its project, and an unknown binding is refused naming the valid keys"):
    value =>
      live(value): running =>
        for
          _ <- project(running, "TIK")
          created <- callTool(running, "create_issue", Json.obj("title" -> "Bound".asJson), project = Some("TIK"))
          unbound <- callTool(running, "create_issue", Json.obj("title" -> "Unbound".asJson))
          refused <- modern(running, "tools/list", project = Some("NOPE"))
        yield
          assertEquals(
            created.hcursor.downField("structuredContent").downField("row").get[String]("id"),
            Right("TIK-1")
          )
          assertEquals(unbound.hcursor.downField("structuredContent").get[String]("argument"), Right("project"))
          assertEquals(refused._1, 404)
          assertEquals(refused._2.hcursor.get[List[String]]("valid_keys"), Right(List("TIK")))

  home.test("the older revision initialises a session and records the client as the actor"): value =>
    live(value): running =>
      val initialize = Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "id" -> 1.asJson,
        "method" -> "initialize".asJson,
        "params" -> Json.obj(
          "protocolVersion" -> Mcp.legacy.asJson,
          "capabilities" -> Json.obj(),
          "clientInfo" -> Json.obj("name" -> "legacy-client".asJson, "version" -> "1".asJson)
        )
      )
      val call = Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "id" -> 2.asJson,
        "method" -> "tools/call".asJson,
        "params" -> Json.obj(
          "name" -> "create_issue".asJson,
          "arguments" -> Json.obj("title" -> "Legacy".asJson, "project" -> "TIK".asJson)
        )
      )
      for
        _ <- project(running, "TIK")
        session <- sessionFor(running, initialize)
        created <- running.send(Method.POST, "/mcp", Some(call), List("Mcp-Session-Id" -> session))
        unknown <- running.send(Method.POST, "/mcp", Some(call), List("Mcp-Session-Id" -> "no-such-session"))
        history <- running.send(Method.GET, "/api/issues/TIK-1/events")
      yield
        assertEquals(created._1, 200)
        assertEquals(unknown._1, 404)
        assertEquals(
          history._2.hcursor.downField("events").downArray.get[String]("actor"),
          Right("mcp:legacy-client")
        )

  home.test("the stateless revision refuses requests missing metadata, and methods it removed"): value =>
    live(value): running =>
      val bare = Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "id" -> 1.asJson,
        "method" -> "ping".asJson,
        "params" -> Json.obj("_meta" -> Json.obj())
      )
      for
        missing <- running.send(
          Method.POST,
          "/mcp",
          Some(bare),
          List("MCP-Protocol-Version" -> Mcp.modern, "Mcp-Method" -> "ping")
        )
        removed <- modern(running, "ping")
      yield
        assertEquals(missing._1, 400)
        assertEquals(missing._2.hcursor.downField("error").get[Int]("code"), Right(-32602))
        assertEquals(removed._1, 404)
        assertEquals(removed._2.hcursor.downField("error").get[Int]("code"), Right(-32601))

  /** The session id comes back as a header, which the raw helper does not return, so this one request reads it. */
  private def sessionFor(running: Live, initialize: Json): IO[String] =
    import org.http4s.Request
    import org.http4s.Uri
    import org.http4s.circe.*
    val request = Request[IO](Method.POST, Uri.unsafeFromString(s"${running.base}/mcp")).withEntity(initialize)
    running.client
      .run(request)
      .use: response =>
        IO.fromOption(response.headers.get(org.typelevel.ci.CIString("Mcp-Session-Id")).map(_.head.value))(
          IllegalStateException("initialize returned no session")
        )
