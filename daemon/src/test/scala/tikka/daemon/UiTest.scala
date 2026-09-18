package tikka.daemon

import cats.effect.IO
import org.http4s.CacheDirective
import org.http4s.Method
import org.http4s.headers.`Cache-Control`
import org.http4s.headers.`Last-Modified`

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** The web UI as the daemon serves it: deep links open the app, and a missing asset is never answered with HTML. */
class UiTest extends RunningDaemon:
  home.test("every page outside the API and MCP is the app, so deep links open it"): value =>
    live(value): running =>
      for
        root <- running.send(Method.GET, "/")
        issue <- running.send(Method.GET, "/i/TIK-42")
        tree <- running.send(Method.GET, "/tree/TIK-5")
      yield List(root, issue, tree).foreach: (status, body) =>
        assertEquals(status, 200)
        assert(body.asString.exists(_.contains("/assets/main.js")), body)

  home.test("the built assets are served from the jar, and an unknown one is a 404"): value =>
    live(value): running =>
      for
        script <- running.send(Method.GET, "/assets/main.js")
        styles <- running.send(Method.GET, "/assets/tikka.css")
        missing <- running.send(Method.GET, "/assets/nope.js")
        escape <- running.send(Method.GET, "/assets/../index.html")
      yield
        assertEquals(script._1, 200)
        assertEquals(styles._1, 200)
        assertEquals(missing._1, 404)
        assertNotEquals(escape._1, 200)

  // The names carry no content hash, so a browser left to guess how long to keep main.js will go on running an old
  // app against an upgraded daemon.
  home.test("the app and its assets are always revalidated"): value =>
    live(value): running =>
      for
        script <- running.headersOf(Method.GET, "/assets/main.js")
        shell <- running.headersOf(Method.GET, "/i/TIK-42")
      yield List(script, shell).foreach: headers =>
        assertEquals(headers.get[`Cache-Control`].map(_.values.head), Some(CacheDirective.`no-cache`()))
        assert(headers.get[`Last-Modified`].isDefined, "nothing to revalidate against")

  home.test("the API keeps its own not-found answers"): value =>
    live(value): running =>
      running
        .send(Method.GET, "/api/nope")
        .map: (status, body) =>
          assertEquals(status, 404)
          assert(!body.asString.exists(_.contains("<html")), body)

  home.test("with a development directory, files there win over the jar"): value =>
    val directory = value.dir.resolve("ui-dev")
    IO.blocking:
      Files.createDirectories(directory)
      Files.write(directory.resolve("main.js"), "console.log('from disk')".getBytes(UTF_8))
    *> live(value, uiDirectory = Some(directory)): running =>
      running
        .send(Method.GET, "/assets/main.js")
        .map: (status, body) =>
          assertEquals(status, 200)
          assertEquals(body.asString, Some("console.log('from disk')"))
