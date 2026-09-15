---
id: T09
title: Design the HTTP API shape
type: grilling
status: open
assignee: null
blocked-by: [T07]
---

## Question

The HTTP API serves the web UI and the CLI, and the MCP server sits on the same core.
The question is what relationship those bear to each other.

- **Is HTTP the base layer that MCP adapts, or are both thin adapters over an internal
  core API?** The second is cleaner and keeps MCP from inheriting REST's shape, but it
  means three things to keep in step instead of two. Decide deliberately — this is the
  "one core, four surfaces" claim being cashed in or quietly abandoned.
- Does the HTTP API **mirror the MCP tool contract** (an endpoint per tool, including
  intent operations like claim and close), or is it resource-shaped REST with the intent
  operations expressed as sub-resources?
- **Content negotiation**: the web UI wants HTML, the CLI wants data. One set of routes
  serving both, or separate route trees?
- **Local-only consequences.** No auth was settled, so what stops a browser page on
  another origin from talking to the daemon? CORS and origin checks are the one piece of
  security that still matters on localhost, and it is easy to skip by accident.
- **Versioning**: does a local-only API need any, or is that ceremony to refuse?

Depends on [Design the MCP tool contract](T07-mcp-tool-contract.md) — the MCP contract is
primary, so HTTP is designed against it rather than the reverse.

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
listing takes one `?q=` parameter in the shared syntax and returns an opaque next cursor.
No per-filter query parameters.
