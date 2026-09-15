---
id: T13
title: Survey the Scala ecosystem for tikka's four surfaces
type: research
status: open
assignee: scala-survey-2
blocked-by: []
---

## Question

What does the Scala 3 ecosystem offer today for each of tikka's surfaces, given the
Typelevel stack and Laminar are fixed? Concrete facts — versions, release dates, commit
activity, Scala 3 support — not hedged prose.

1. **MCP servers in Scala or on the JVM.** Highest value. Is there any Scala-native MCP
   library? If the path is interop with the official Java SDK, that SDK is Reactor /
   Reactive-Streams based — how cleanly does it bridge into cats-effect via
   `fs2-reactive-streams`, and at what cost? Which protocol revision does each support?
   Can an MCP endpoint mount inside an existing http4s server?
2. **Typelevel HTTP.** http4s health and Scala 3 support. For tapir: can endpoint
   definitions be shared between the JVM server and a Scala.js client to generate a
   typed client for Laminar? That code-sharing question matters disproportionately here.
3. **Embedded persistence, Typelevel-compatible.** Must run in-process with zero
   external services, survive restarts, express recursive queries (hierarchy walks,
   transitive closure over blocking edges), support an atomic compare-and-set, and
   append to the event log in the *same* transaction. doobie + SQLite, magnum, Quill.
   Verify whether skunk is Postgres-wire-only and therefore disqualified.
4. **Laminar and Scala.js.** Versions and activity. Realistic build toolchain (sbt vs
   scala-cli vs mill, plus Vite). How cross-compilation of shared domain types works in
   practice. Which JSON libraries cross-compile cleanly, and their code-size cost on JS.
5. **CLI startup**, targeting sub-100ms. Does cats-effect work on Scala Native? Scala
   Native's maturity and Scala 3 support. GraalVM native-image with cats-effect and any
   reflection pain. Measured figures where available.
6. **Cross-cutting.** Can HTTP API, web UI serving, and MCP all mount in one http4s
   server in one daemon? How does MCP stdio coexist with a long-running HTTP daemon —
   thin stdio proxy, or clients connecting to a local HTTP endpoint directly?

Landscape only; the decisions it feeds are separate tickets.
