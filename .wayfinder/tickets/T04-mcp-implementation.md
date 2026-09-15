---
id: T04
title: Choose the MCP server implementation and transport
type: grilling
status: open
assignee: saqib
blocked-by: [T13]
---

## Question

How does tikka speak MCP, and over what transport?

Since MCP is the *primary* interface rather than a bolt-on, this choice carries more
weight than a typical library pick — and on the Typelevel stack it is also where the
costs accepted in [ADR 0001](../../docs/adr/0001-scala-with-typelevel-stack.md) land.

- **Implementation.** A Scala-native MCP library if one credibly exists, interop with
  the official Java SDK, or hand-rolled JSON-RPC over http4s. The Java SDK is
  conformance-tested but Reactor-based, so reaching cats-effect means an
  `fs2-reactive-streams` bridge sitting on the primary interface. Hand-rolled JSON-RPC
  with circe avoids the bridge but makes tikka own protocol conformance. Read
  [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md).
- **Transport.** Streamable HTTP mounted as http4s routes in the daemon, or stdio. The
  store is owned by a long-running daemon, so stdio means a thin proxy process forwarding
  to it. Claude Code connects to a local HTTP endpoint directly
  (`claude mcp add --transport http`), which may make stdio unnecessary for the first
  customer — decide whether any required client forces it.
- **If stdio is kept:** blocking stdin reads inside a cats-effect runtime must be
  confined to the blocking pool or they starve compute threads. And what happens when
  the daemon is not running — does the proxy start it, or fail with a message?
- **Resources and prompts**, or tools alone? Agent-first argues for at least considering
  resources for issue bodies.
- **Spec revision targeting.** The current MCP revision is 2026-07-28 while the JVM
  field lags at 2025-11-25, so spec drift is a standing condition. Does tikka pin a
  revision and accept going stale, or commit to following — and who absorbs the drift
  under each implementation choice?

**Inherited from [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md):**
three cats-effect MCP libraries expose http4s routes: ingarabr/scala-mcp-sdk (2025-11-25, dormant since
May), linkyard/scala-effect-mcp (2025-06-18) and andimiller/scala-mcp (JVM/JS/Native). All are
single-maintainer projects. The Java SDK 2.0.1 is conformance-tested at 2025-11-25, but its server
transports are servlet-based, so http4s needs a hand-written transport. chimp's streaming path does not
cover cats-effect. The only 2026-07-28 implementation, fast-mcp-scala, is ZIO. Streamable HTTP lets
Claude Code connect to the daemon directly; stdio-only clients need a proxy, which the native CLI can
provide.

**Inherited from [Design the MCP tool contract](T07-mcp-tool-contract.md):**
nine tools, tools only (no resources or prompts). The implementation must support `outputSchema` and
`structuredContent` next to a text block, and domain errors as `isError` results. It must read a
project binding from the endpoint URL (`…/mcp?project=TIK`) and derive the actor from the
connection. Weigh candidate libraries against those requirements.
