---
id: T04
title: Choose the MCP server implementation and transport
type: grilling
status: open
assignee: null
blocked-by: [T01]
---

## Question

How does tikka speak MCP, and over what transport?

Since MCP is the *primary* interface rather than a bolt-on, this choice carries more
weight than a typical library pick.

- **Implementation**: a Clojure MCP library, interop with the official Java SDK, or
  hand-rolled JSON-RPC? Read
  [Survey the Clojure ecosystem for tikka's four surfaces](T01-clojure-ecosystem-survey.md)
  for what exists and how actively it is maintained. Weigh how much of the protocol each covers against how
  much of it tikka actually needs.
- **Transport**: stdio or HTTP. This is the harder half. Clients commonly launch an MCP
  server as a stdio subprocess, but tikka's store is owned by a long-running daemon —
  so either the stdio process is a thin client proxying to the daemon over HTTP, or the
  daemon serves MCP over HTTP directly and the client connects to it.
- **Consequence to settle explicitly**: if stdio is a proxy, what happens when the
  daemon is not running? Does the proxy start it, or fail with a message?
- Does tikka expose MCP **resources** and **prompts**, or tools alone? Agent-first
  argues for at least considering resources for issue bodies.
