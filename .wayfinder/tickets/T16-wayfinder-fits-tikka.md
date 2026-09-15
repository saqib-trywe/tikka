---
id: T16
title: Confirm wayfinder fits tikka's contract
type: grilling
status: open
assignee: saqib
blocked-by: []
---

## Question

The map's destination says it is done "when wayfinder itself could be hosted on tikka". Before anyone builds, walk
every wayfinder operation against the settled contract and name each gap: the nine MCP tools
([Design the MCP tool contract](T07-mcp-tool-contract.md)), the query grammar
([Define the shared query and filter grammar](T08-query-grammar.md)), the invariants
([Define tikka's core invariants](T02-core-invariants.md)) and mentions
([Settle body and mention conventions](T15-body-and-mention-conventions.md)).

Operations to walk: the map as an issue with a label and a body holding Destination, Notes, Decisions so far, the
unmapped items and Out of scope; tickets as child issues with a type label; blocking; the frontier query and "the first
frontier ticket in order"; claiming by assignee before work, with concurrent sessions skipping claimed tickets; recording a
resolution, closing, and appending to the map's Decisions; create-then-wire; ruling a ticket out of scope; updating or
retiring invalidated tickets without deletion; loading the map at low resolution; referring to tickets by name.

Resolve with: each operation's tikka expression, and any gap either fixed by amending a ticket or consciously accepted.
Tikka still never learns what wayfinder is.
