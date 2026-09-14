---
id: T11
title: Walking skeleton — four surfaces, one process
type: prototype
status: open
assignee: null
blocked-by: [T03, T04, T05, T06]
---

## Question

The one execution ticket on this map. Everything else produces decisions; this produces
throwaway code that proves the central technical assumption before the spec is handed
off: **the surfaces genuinely share one core.**

The survey already corrected the shape of that claim. Sub-100ms startup rules the CLI
out of the daemon process, so the target is **web UI, HTTP API and MCP mounted in one
Ring handler in a single daemon, plus a separate babashka CLI** against it — not four
surfaces in one process. Proving that is still the point; the arrangement is just known
now rather than assumed.

Build the thinnest possible end-to-end slice. One issue type, one field beyond the id,
no invariants, no polish:

- The daemon starts and holds the chosen store.
- Create an issue via the **CLI**, and measure real startup time against the budget set
  in [Choose the CLI approach](T06-cli-approach.md).
- See it in the **web UI**.
- Read it back over the **HTTP API**.
- Read it over **MCP** from a real MCP client, not a test harness — this is the leg most
  likely to break, and a mock would prove nothing.

What the prototype must answer:

- Does the MCP transport decision from
  [Choose the MCP server implementation and transport](T04-mcp-implementation.md)
  actually survive contact with a real client, and does it coexist with a long-running HTTP server?
- Is the "one core, four adapters" structure real, or did something force duplication —
  particularly across the daemon/babashka boundary, where shared code is constrained?
- Where did the friction actually turn up, versus where the map predicted it would?

Throwaway code. It is evidence for the spec, not the first commit of tikka — resist
growing it into the product.
