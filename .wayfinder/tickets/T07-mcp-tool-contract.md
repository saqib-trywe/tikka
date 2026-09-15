---
id: T07
title: Design the MCP tool contract
type: grilling
status: open
assignee: null
blocked-by: [T02, T03]
---

## Question

The most consequential design on the map: MCP is tikka's primary interface, so this
contract *is* the product's API.

Settled already: coarse CRUD plus a small set of intent tools, ~7–8 total, where an
intent tool is justified only by an invariant or a race it enforces — never by
convenience. `claim_issue` (atomic compare-and-set), `close_issue` (demands resolution
and comment) and `frontier` are the known members.

[Define tikka's core invariants](T02-core-invariants.md) added hard requirements this
contract must carry:

- The assignee is **not** settable through generic update. It needs `claim`, `release`
  and `reassign` (naming the expected holder) — so the ~7–8 tool budget is under
  pressure. Decide whether those are three tools or one assignee tool with a mode.
- `close_issue` **returns the issues it newly unblocked**.
- Reopen exists and is invariant-checked — a tool, or a mode of close?
- Writes to title/body/labels take an **optional expected version**; stale → rejected.
- Every invariant rejection **names the conflict** — the open children, the open
  blockers, or the cycle path. Design the error shape so an agent can act on it without a
  follow-up query.
- There is no delete tool, by rule.

What this ticket must produce:

- The **full tool list** with names, argument schemas, and return shapes.
- **Response design for an LLM reader.** How much of an issue does `get_issue` return —
  body, comments, events, edges, all of it? Token cost is a real constraint; an agent
  listing 40 issues must not receive 40 full bodies. What does the list/detail split
  look like?
- **How errors read.** An agent that loses a claim race, or trips a cycle rejection,
  must understand what happened and what to do instead from the error alone.
- **Whether tools are project-scoped or global**, and how an agent says which project
  it means without being told twice.
- Whether write tools are idempotent, and what a retry after an ambiguous failure does.

Depends on [Define tikka's core invariants](T02-core-invariants.md) for the invariants
being enforced, and [Choose the persistence engine](T03-persistence-engine.md) for what
the store can do atomically.

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
list/search tools take a single `query` string in the shared syntax, documented in the
tool description; results are cursor-paged (default 50, max 200); query errors name the bad
token and suggest alternatives. `ready` is a query predicate — decide whether a dedicated
`frontier`-style tool still earns its place, or whether search with `ready` suffices.

**Inherited from [Choose the persistence engine](T03-persistence-engine.md):**
the version moves only on title, body and label changes. Rank is any decimal number, so a
"move between" tool computes a midpoint and can run out of precision in theory (refused, conflict
named). Writes run one at a time and every write is atomic with its events. No write accepts a
timestamp. Cursors guarantee that unchanged issues appear exactly once, and issues whose sort key
moves mid-paging may repeat or vanish, so document that in paging tool descriptions. `text:` is a
case-insensitive substring match.
