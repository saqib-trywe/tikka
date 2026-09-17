package tikka.integration

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Ref
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import tikka.cli.Cli
import tikka.cli.HttpBackend
import tikka.cli.Processes
import tikka.cli.Recorded
import tikka.daemon.Live
import tikka.daemon.RunningDaemon
import tikka.shared.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** The CLI against a real daemon, driven in process with a recorded terminal. */
class CliIntegrationTest extends RunningDaemon:
  final case class Run(code: ExitCode, out: String, err: String, written: Vector[String])

  /** A repository directory bound to TIK, inside the test's home. */
  private def repo(running: Live): IO[Path] =
    IO.blocking:
      val directory = running.home.dir.resolve("repo")
      Files.createDirectories(directory)
      Files.write(directory.resolve(".tikka"), "project = \"TIK\"\n".getBytes(UTF_8))
      directory

  private def tikka(
      running: Live,
      directory: Path,
      arguments: List[String],
      input: List[String] = Nil,
      processes: Processes = Processes.system,
      variables: Map[String, String] = Map.empty
  ): IO[Run] =
    for
      _ <- IO.blocking(
        Files.write(running.home.dir.resolve("config.toml"), s"port = ${running.port}\n".getBytes(UTF_8))
      )
      recorded <- Recorded(
        Map(
          "TIKKA_HOME" -> running.home.dir.toString,
          "HOME" -> running.home.dir.toString,
          "USER" -> "tester",
          "PATH" -> sys.env.getOrElse("PATH", "")
        ) ++ variables,
        directory,
        input
      )
      written <- Ref.of[IO, Vector[String]](Vector.empty)
      code <- Cli.run(
        arguments,
        recorded.environment,
        processes,
        HttpBackend.resource,
        line => written.update(_ :+ line)
      )
      out <- recorded.out
      err <- recorded.err
      lines <- written.get
    yield Run(code, out, err, lines)

  private def json(text: String): Json = parse(text).fold(error => fail(s"not JSON: $text ($error)"), identity)

  home.test("a bound repository creates, finds and shows issues, as text and as JSON"): value =>
    live(value): running =>
      for
        directory <- repo(running)
        project <- tikka(running, directory, List("project", "new", "TIK", "Tikka"))
        created <- tikka(running, directory, List("new", "First issue", "--label", "bug", "-m", "from the CLI"))
        found <- tikka(running, directory, List("search", "ready"))
        shown <- tikka(running, directory, List("show", "TIK-1", "--json"))
      yield
        assertEquals(project.code, ExitCode.Success)
        assertEquals(created.code, ExitCode.Success)
        assert(created.out.startsWith("TIK-1  open  First issue  [bug]"), created.out)
        assert(found.out.startsWith("project:TIK ready: 1 issue"), found.out)
        assertEquals(json(shown.out).hcursor.get[List[String]]("labels"), Right(List("bug")))
        assertEquals(json(shown.out).hcursor.downField("comments").downArray.get[String]("actor"), Right("cli"))

  home.test("claims name their holder, a conflict exits 1 with the code first, and reassigning to none clears it"):
    value =>
      live(value): running =>
        for
          directory <- repo(running)
          _ <- tikka(running, directory, List("project", "new", "TIK", "Tikka"))
          _ <- tikka(running, directory, List("new", "Contended"))
          claimed <- tikka(running, directory, List("claim", "TIK-1", "--as", "saqib/wf-1"))
          conflict <- tikka(running, directory, List("claim", "TIK-1", "--as", "someone-else"))
          released <- tikka(running, directory, List("release", "TIK-1", "--as", "saqib/wf-1"))
          fromEnv <- tikka(
            running,
            directory,
            List("claim", "TIK-1"),
            variables = Map("TIKKA_ASSIGNEE" -> "saqib/wf-2")
          )
          cleared <- tikka(running, directory, List("reassign", "TIK-1", "--from", "saqib/wf-2", "--to", "none"))
        yield
          assertEquals(claimed.code, ExitCode.Success)
          assertEquals(conflict.code, ExitCode(1))
          assert(conflict.err.startsWith("claim_conflict: "), conflict.err)
          assertEquals(released.code, ExitCode.Success)
          assert(fromEnv.out.contains("claimed by saqib/wf-2"), fromEnv.out)
          assert(!cleared.out.contains("claimed by"), cleared.out)

  home.test("edit flags add and remove labels, clear a parent and add a blocker"): value =>
    live(value): running =>
      for
        directory <- repo(running)
        _ <- tikka(running, directory, List("project", "new", "TIK", "Tikka"))
        _ <- tikka(running, directory, List("new", "Parent"))
        _ <- tikka(running, directory, List("new", "Child", "--parent", "TIK-1", "--label", "a", "--label", "b"))
        _ <- tikka(running, directory, List("new", "Blocker"))
        edited <- tikka(
          running,
          directory,
          List("edit", "TIK-2", "--label=-a", "--label", "+c", "--parent", "none", "--blocked-by", "TIK-3")
        )
        shown <- tikka(running, directory, List("show", "TIK-2", "--json"))
      yield
        assertEquals(edited.code, ExitCode.Success, edited.err)
        val issue = json(shown.out).hcursor
        assertEquals(issue.get[List[String]]("labels"), Right(List("b", "c")))
        assert(issue.downField("parent").failed, shown.out)
        assertEquals(issue.downField("blockers").downArray.get[String]("id"), Right("TIK-3"))

  home.test("with no flags, edit opens the body in $EDITOR and saves it"): value =>
    live(value): running =>
      for
        directory <- repo(running)
        _ <- tikka(running, directory, List("project", "new", "TIK", "Tikka"))
        _ <- tikka(running, directory, List("new", "Needs words"))
        editor <- IO.blocking:
          val script = running.home.dir.resolve("editor.sh")
          Files.write(script, "#!/bin/sh\nprintf 'written in the editor' >> \"$1\"\n".getBytes(UTF_8))
          script.toFile.setExecutable(true): Unit
          script
        edited <- tikka(running, directory, List("edit", "TIK-1"), variables = Map("EDITOR" -> editor.toString))
        shown <- tikka(running, directory, List("show", "TIK-1", "--json"))
      yield
        assertEquals(edited.code, ExitCode.Success, edited.err)
        assertEquals(json(shown.out).hcursor.get[String]("body"), Right("written in the editor"))
        assertEquals(json(shown.out).hcursor.get[Int]("version"), Right(2))

  home.test("an edit that lands while the editor is open is refused as stale, and the typed text is kept"): value =>
    live(value): running =>
      // Stands in for the editor: someone else edits the issue, then the user saves their own text.
      val racing = new Processes:
        def interactive(command: List[String], extraEnvironment: Map[String, String]): IO[Int] =
          val file = Path.of(command.last)
          val id = IssueId.parse("TIK-1").toOption.get
          val other = UpdateIssue.nothing.copy(body = Some(BodyChange.Replace(Body("an agent got there first"))))
          running.daemon.core.update(id, other, Actor(Surface.Mcp, Some("agent"))) *>
            IO.blocking(Files.write(file, "my careful words".getBytes(UTF_8))).as(0)
        def capture(command: List[String], directory: Option[Path]): IO[(Int, String)] = IO.pure((0, ""))
      for
        directory <- repo(running)
        _ <- tikka(running, directory, List("project", "new", "TIK", "Tikka"))
        _ <- tikka(running, directory, List("new", "Contested body"))
        edited <- tikka(running, directory, List("edit", "TIK-1"), processes = racing)
        kept <- IO.blocking(String(Files.readAllBytes(running.home.dir.resolve("tmp").resolve("TIK-1.md")), UTF_8))
      yield
        assertEquals(edited.code, ExitCode(1))
        assert(edited.err.startsWith("stale_version: "), edited.err)
        assert(edited.err.contains("your edit is kept in"), edited.err)
        assertEquals(kept, "my careful words")

  home.test("bulk close takes children first and stops at the first rejection, listing what it closed"): value =>
    live(value): running =>
      for
        directory <- repo(running)
        _ <- tikka(running, directory, List("project", "new", "TIK", "Tikka"))
        _ <- tikka(running, directory, List("new", "Parent"))
        _ <- tikka(running, directory, List("new", "First child", "--parent", "TIK-1"))
        _ <- tikka(running, directory, List("new", "Blocker"))
        _ <- tikka(running, directory, List("new", "Second child", "--parent", "TIK-1", "--blocked-by", "TIK-3"))
        stopped <- tikka(
          running,
          directory,
          List("close", "--query", "id:TIK-1,TIK-2,TIK-4", "done", "-m", "wrapping up")
        )
        _ <- tikka(running, directory, List("close", "TIK-3", "done", "-m", "unblocking"))
        rest <- tikka(running, directory, List("close", "--query", "id:TIK-1,TIK-2,TIK-4", "done", "-m", "wrapping up"))
        parent <- tikka(running, directory, List("show", "TIK-1", "--json"))
      yield
        assertEquals(stopped.code, ExitCode(1))
        assert(stopped.out.startsWith("closed 1\nTIK-2"), stopped.out)
        assert(stopped.err.contains("stopped at TIK-4"), stopped.err)
        assert(stopped.err.contains("open_blockers"), stopped.err)
        assertEquals(rest.code, ExitCode.Success, rest.err)
        assertEquals(json(parent.out).hcursor.get[String]("status"), Right("closed"))

  home.test("tikka mcp forwards both revisions over stdio with the binding applied, and answers no notification"):
    value =>
      live(value): running =>
        val meta = Json.obj(
          "io.modelcontextprotocol/protocolVersion" -> "2026-07-28".asJson,
          "io.modelcontextprotocol/clientCapabilities" -> Json.obj(),
          "io.modelcontextprotocol/clientInfo" -> Json.obj("name" -> "stdio-client".asJson, "version" -> "1".asJson)
        )
        val lines = List(
          Json.obj(
            "jsonrpc" -> "2.0".asJson,
            "id" -> 1.asJson,
            "method" -> "tools/list".asJson,
            "params" -> Json.obj("_meta" -> meta)
          ),
          Json.obj("jsonrpc" -> "2.0".asJson, "method" -> "notifications/initialized".asJson),
          Json.obj(
            "jsonrpc" -> "2.0".asJson,
            "id" -> 2.asJson,
            "method" -> "initialize".asJson,
            "params" -> Json.obj(
              "protocolVersion" -> "2025-11-25".asJson,
              "capabilities" -> Json.obj(),
              "clientInfo" -> Json.obj("name" -> "legacy-stdio".asJson, "version" -> "1".asJson)
            )
          ),
          Json.obj(
            "jsonrpc" -> "2.0".asJson,
            "id" -> 3.asJson,
            "method" -> "tools/call".asJson,
            "params" -> Json.obj("name" -> "search_issues".asJson, "arguments" -> Json.obj("query" -> "ready".asJson))
          )
        ).map(_.noSpaces)
        for
          directory <- repo(running)
          _ <- tikka(running, directory, List("project", "new", "TIK", "Tikka"))
          proxied <- tikka(running, directory, List("mcp"), input = lines)
        yield
          assertEquals(proxied.code, ExitCode.Success, proxied.err)
          assertEquals(proxied.written.size, 3, proxied.written.mkString("\n"))
          val replies = proxied.written.map(json)
          assertEquals(replies(0).hcursor.downField("result").downField("tools").as[List[Json]].map(_.size), Right(9))
          assertEquals(replies(1).hcursor.downField("result").get[String]("protocolVersion"), Right("2025-11-25"))
          assertEquals(
            replies(2).hcursor.downField("result").downField("structuredContent").get[String]("effective_query"),
            Right("project:TIK ready")
          )

  home.test("init binds the directory, refuses an unknown project, and prints the MCP entry without Claude Code"):
    value =>
      live(value): running =>
        val fresh = running.home.dir.resolve("fresh")
        for
          _ <- IO.blocking(Files.createDirectories(fresh))
          unknown <- tikka(running, fresh, List("init", "TIK"), variables = Map("PATH" -> ""))
          bound <- tikka(running, fresh, List("init", "TIK", "--new", "Tikka"), variables = Map("PATH" -> ""))
          file <- IO.blocking(String(Files.readAllBytes(fresh.resolve(".tikka")), UTF_8))
        yield
          assertEquals(unknown.code, ExitCode(1))
          assert(unknown.err.contains("tikka init TIK --new"), unknown.err)
          assertEquals(bound.code, ExitCode.Success, bound.err)
          assertEquals(file, "project = \"TIK\"\n")
          assert(bound.out.contains(s"http://127.0.0.1:${running.port}/mcp?project=TIK"), bound.out)

  home.test("daemon status sees the running daemon, and export runs the jar against this home"): value =>
    live(value): running =>
      val recorded = Ref.unsafe[IO, List[(List[String], Map[String, String])]](Nil)
      val recorder = new Processes:
        def interactive(command: List[String], extraEnvironment: Map[String, String]): IO[Int] =
          recorded.update(_ :+ (command, extraEnvironment)).as(0)
        def capture(command: List[String], directory: Option[Path]): IO[(Int, String)] = IO.pure((0, ""))
      for
        directory <- repo(running)
        javaHome <- IO.blocking:
          Files.createDirectories(running.home.dir.resolve("lib"))
          Files.write(running.home.dir.resolve("lib").resolve("tikka-daemon.jar"), Array.emptyByteArray)
          val bin = running.home.dir.resolve("jdk").resolve("bin")
          Files.createDirectories(bin)
          Files.write(bin.resolve("java"), "#!/bin/sh\n".getBytes(UTF_8))
          bin.resolve("java").toFile.setExecutable(true): Unit
          running.home.dir.resolve("jdk")
        status <- tikka(running, directory, List("daemon", "status"))
        exported <- tikka(
          running,
          directory,
          List("daemon", "export", "out.jsonl"),
          processes = recorder,
          variables = Map("JAVA_HOME" -> javaHome.toString)
        )
        commands <- recorded.get
      yield
        assertEquals(status.code, ExitCode.Success)
        assert(status.out.startsWith(s"running at http://127.0.0.1:${running.port}"), status.out)
        assertEquals(exported.code, ExitCode.Success, exported.err)
        val (command, environment) = commands.head
        assertEquals(
          command.take(2),
          List(javaHome.resolve("bin").resolve("java").toString, "--enable-native-access=ALL-UNNAMED")
        )
        assertEquals(command.takeRight(2), List("export", directory.resolve("out.jsonl").toString))
        assertEquals(environment.get("TIKKA_HOME"), Some(running.home.dir.toString))
