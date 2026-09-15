---
id: T06
title: Choose the CLI approach
type: grilling
status: open
assignee: saqib
blocked-by: [T13]
---

## Question

How is the CLI built and distributed?

The CLI is a thin HTTP client to the daemon with full CRUD, so its logic is trivial. The
decision is **startup time**: a CLI that takes a second to list issues will not get used.
The JVM startup floor is disqualifying on its own, so the CLI cannot be a plain JVM
process — this much survived from the Clojure survey.

- What is the startup budget? Sub-100ms is the bar for a tool that feels instant.
- Which approach hits it: **Scala Native**, **GraalVM native-image**, or something else?
  Read [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md)
  for measured figures and for whether cats-effect and the chosen HTTP client actually
  work on each.
- **Does the CLI use the Typelevel stack at all?** Initialising a cats-effect runtime
  costs startup time, and a native target may constrain which libraries load. A CLI that
  makes one HTTP call and prints may be better served by a minimal client with no effect
  system — which is legitimate, but it means the CLI shares less of the core. Decide how
  much sharing is worth how many milliseconds.
- **Shared code.** Domain types and codecs cross-compiled to the native target, or a
  small duplicated client? Duplication is the honest answer if cross-building costs more
  than the code it saves.
- **Output shape.** Human-readable tables by default with a machine flag for scripting,
  or structured always?
- **Build cost.** Native builds are slow and have their own failure modes; what does that
  do to the edit-run loop while developing the CLI?

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
queries are a positional argument in the shared syntax. Decide whether the CLI parses
locally (the parser must then build for the native target) or sends the raw string and lets
the daemon reject it — the latter keeps the CLI thinner at the cost of a round trip for typos.

**Inherited from [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md):**
startup no longer separates the options. cats-effect's published hello world takes 7.5 ms on Scala
Native (0.5.12) and 11.2 ms on GraalVM native-image. On Native, the Ember client, fs2-io, sttp 4,
circe, jsoniter-scala, decline and tapir-core all publish, so a CLI on the Typelevel stack costs nothing
in startup. The deciding factors are build time, binary size, and whether the query parser
cross-builds to Native for local validation. The CLI is also the natural stdio-to-HTTP MCP proxy
for clients that only spawn stdio servers.

**Inherited from [Choose the MCP server implementation and transport](T04-mcp-implementation.md):**
the CLI carries `tikka mcp`, a stdio-to-HTTP proxy for stdio-only MCP clients: newline-delimited
JSON-RPC on stdin forwarded to the daemon's `/mcp` with the repo's binding applied. On Scala Native the
cats-effect runtime is documented as single-threaded, with no blocking pool, so reading stdin must not block the
only thread. Verify how fs2-io reads stdin on Native before committing to cats-effect here.

**Inherited from [Design the HTTP API shape](T09-http-api-shape.md):**
the CLI's HTTP client is the tapir-derived sttp client from the shared module, so the endpoint
definitions must build for Scala Native. It sends `Tikka-Project` from the repo binding, calls
`GET /api/meta` to warn on a daemon version mismatch, and switches on the error body's `error`
code (statuses are only 404/400/409).
