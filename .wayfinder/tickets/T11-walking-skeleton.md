---
id: T11
title: Walking skeleton — surfaces over one core
type: prototype
status: open
assignee: null
blocked-by: [T03, T04, T05, T06, T14]
---

## Question

The one execution ticket on this map. Everything else produces decisions; this produces
throwaway code that proves the central technical assumption before the spec is handed
off: **the surfaces genuinely share one core.**

The target arrangement: **HTTP API, Laminar assets and MCP served by one http4s server in
a single daemon**, plus a **separate native CLI** against its HTTP API. The JVM startup
floor rules the CLI out of the daemon process, so this is not four surfaces in one
process — proving the arrangement above is still the point.

Build the thinnest end-to-end slice. One issue, one field beyond the id, no invariants,
no polish:

- The daemon starts on cats-effect and holds the chosen store.
- Create an issue via the **CLI**, and measure real startup time against the budget set
  in [Choose the CLI approach](T06-cli-approach.md).
- See it in the **Laminar UI**, using the shared cross-compiled domain type.
- Read it back over the **HTTP API**.
- Read it over **MCP** from a real MCP client, not a test harness — the leg most likely to
  break, and a mock would prove nothing.

What the prototype must answer:

- Does the transport and implementation from
  [Choose the MCP server implementation and transport](T04-mcp-implementation.md) survive
  contact with a real client — in particular, does any Reactor bridge behave under
  cats-effect cancellation?
- Is "one core, many adapters" real, or did something force duplication — across the
  JVM/Scala.js boundary, or across the JVM/native-CLI boundary?
- Did either revisit condition in [ADR 0001](../../docs/adr/0001-scala-with-typelevel-stack.md)
  trigger: a disproportionately painful MCP bridge, or a CLI that can only hit its startup
  budget by abandoning the shared core?
- Where did the friction actually turn up, versus where the map predicted?

Throwaway code. Evidence for the spec, not the first commit of tikka — resist growing it
into the product.

**Inherited from [Design the HTTP API shape](T09-http-api-shape.md):**
the skeleton proves one core under two adapters: tapir endpoints in a cross-compiled shared module,
interpreted to http4s on the daemon and to sttp clients on Scala.js and Scala Native, with tapir
`Schema` also feeding the hand-rolled MCP layer's tool schemas. If tapir's Native or JS client
does not build, that is the finding.

**Inherited from [Choose the CLI approach](T06-cli-approach.md):**
the skeleton links the CLI for Scala Native and measures `tikka search` against a running daemon
with `hyperfine`. The budget is a 100 ms median, and the multithreaded cats-effect runtime's startup is the
specific unknown. If it misses, try a single-threaded runtime configuration, then the sync curl
backend without cats-effect, then GraalVM, and record which one held.

**Inherited from [Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md):**
build migrations (`user_version`, snapshot before migrating) and the `daemon.lock` from the first
commit; retrofitting either onto a store that already holds real tickets is how data gets lost. Honour
`TIKKA_HOME` everywhere so the skeleton's daemon never touches a real store. Service installation
(`tikka daemon install`) can wait; `tikka daemon run` in the foreground is enough to prove the
surfaces.
