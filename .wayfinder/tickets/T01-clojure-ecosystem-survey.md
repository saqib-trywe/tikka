---
id: T01
title: Survey the Clojure ecosystem for tikka's four surfaces
type: research
status: open
assignee: clj-ecosystem-survey
blocked-by: []
---

## Question

What does the Clojure ecosystem actually offer, today, for each of tikka's four
surfaces — and what is the maturity signal for each option?

Six areas, each needing concrete verifiable facts (names, versions, release dates,
commit activity, notable users) rather than hedged prose:

1. **MCP server implementations** in Clojure or on the JVM. Highest uncertainty on the
   map. Is there a maintained Clojure library? Is the realistic path interop with the
   official Java MCP SDK? Or hand-rolled JSON-RPC? How much of the protocol does each
   cover — tools, resources, prompts, transports?
2. **HTTP routing and server** for a small local API.
3. **Embedded persistence with real query capability.** Must run in-process with zero
   external services, express graph-ish queries (hierarchy, blocking closure), and
   survive restarts.
4. **Web UI approaches** for a local single-user app, weighing build-toolchain cost
   heavily.
5. **CLI**, specifically process startup time — a command-line client must feel instant.
6. **Cross-cutting**: can all four surfaces credibly run from one process or artifact,
   and how does an MCP stdio server coexist with a long-running HTTP server?

Landscape only. No product scope recommendations — those are decisions for the tickets
this one unblocks.
