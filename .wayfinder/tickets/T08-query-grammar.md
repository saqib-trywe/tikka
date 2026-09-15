---
id: T08
title: Define the shared query and filter grammar
type: grilling
status: open
assignee: null
blocked-by: [T02]
---

## Question

All four surfaces filter issues. If each invents its own filter syntax, tikka has four
subtly different query semantics and the bugs live forever in the gaps. One grammar,
four renderings.

The scope floor refused a query *language* — so this is structured filters, and the
work is deciding exactly which dimensions exist and how they compose.

- Which **filter dimensions**: status, resolution, label, assignee, parent, project,
  rank range, text? Which combinations are worth supporting?
- How do filters **compose** — implicit AND only, or are OR and negation needed? (Each
  addition is a step back toward JQL; the bar should be high.)
- **Blocking-aware predicates.** `is:unblocked` and the frontier query are the ones
  that matter and cannot be expressed by field equality alone. Is `frontier` a named
  predicate in the grammar, or a distinct endpoint?
- **Labels**: free-form strings or a controlled set? Wayfinder uses namespaced labels
  (`wayfinder:map`, `wayfinder:research`) — does tikka give namespacing any meaning, or
  are labels opaque? (Opaque keeps tikka ignorant of wayfinder, which is the standing
  preference.)
- How the one grammar **renders** per surface: CLI flags, HTTP query params, MCP tool
  arguments, UI controls.
- **Sort and pagination**, which an agent needs to avoid unbounded results.

Settled by [Define tikka's core invariants](T02-core-invariants.md), and so inputs rather
than questions here:

- **Unblocked** means every blocker is closed, *whatever its resolution*.
- **Default order** is rank ascending, ties broken by issue number — always total.
- The grammar must be able to express **claims older than a given age**; that filter is
  the entire mechanism for finding stale claims, since claims never expire.
- Parent and blocks edges never cross projects, so blocking-aware predicates can be
  evaluated within a single project.

Depends on [Define tikka's core invariants](T02-core-invariants.md), since
blocking-and-closure semantics define what "unblocked" means.
