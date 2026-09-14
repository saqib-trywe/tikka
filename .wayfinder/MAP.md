---
title: "Tikka: an agent-first local work management system"
label: wayfinder:map
---

# Tikka: an agent-first local work management system

## Destination

A spec tight enough that a build session can execute tikka without re-deciding
anything — domain model, persistence, the four surfaces, and the MCP tool contract —
together with a walking skeleton proving the surfaces share one core: web UI, HTTP API
and MCP in a single daemon process, with the CLI as a separate thin client against it.

Done when wayfinder itself could be hosted on tikka: that is the acceptance test.

## Notes

**Domain.** Tikka is a JIRA-like work management system in Clojure, deliberately
simpler, for local single-user use. Four surfaces over one core: web UI, CLI, HTTP
API, MCP server. Vocabulary lives in [CONTEXT.md](../CONTEXT.md) — read it before
using any domain term.

**Skills.** Every session calls `grilling` and `domain-modeling` unless the ticket
says otherwise. Research tickets call `research`. The walking skeleton calls
`prototype`.

**Standing preferences.**

- Plan, don't do. The walking-skeleton ticket is the *single* execution exception on
  this map; nothing else builds product code.
- The scope floor is a refusal list, not a wish list. Defend it: every "while we're
  here" is how you get JIRA.
- Agent-first. When a human surface and the MCP surface disagree, the MCP surface
  wins; the web UI is a window onto what agents did, not an authoring tool.
- Tikka never learns what wayfinder is. Wayfinder's concepts ride on generic labels
  and generic edges, or they don't ride at all.

## Decisions so far

- [Scope and shape settled at charting](tickets/T00-scope-and-shape.md): tikka is an
  agent-first, permanently single-user, local-only tracker with two entities, three
  edge kinds, two statuses, and a daemon that owns the store.
- [Survey the Clojure ecosystem for tikka's four surfaces](tickets/T01-clojure-ecosystem-survey.md):
  one alpha pure-Clojure MCP library versus conformance-tested Java-SDK interop;
  Datalevin and SQLite both meet the atomic-claim and graph-query bar where XTDB 2 and
  Datascript do not; and MCP-over-HTTP mounts as a Ring handler beside the web UI — but
  sub-100ms rules the CLI out of that process, so it is three surfaces in one daemon
  plus a separate babashka client, not four in one.

## Not yet specified

- **Event log schema** — what an event records, how much an update coalesces into one
  event, and how the timeline reads in the UI. Waits on the persistence engine.
- **Body and mention conventions** — how `TIK-nn` is detected in markdown without
  false positives, whether mentions are rendered as live links, and what happens to a
  mention of an issue that is later deleted.
- **MCP failure semantics** — how an agent learns its claim lost a race, or that a
  write was rejected by an invariant. Waits on the MCP tool contract.
- **Search depth** — whether structured filters are joined by full-text over bodies
  and comments, or filters alone carry it.
- **The frontier/graph view** — what the web UI actually draws for blocking edges and
  hierarchy, and whether that is a diagram or an ordered list.
- **Multi-project UX** — how one daemon presents several projects without reintroducing
  a project-picker workflow.
- **Seeding the acceptance test** — actually moving this map onto tikka once it runs.
  Depends on nearly everything above.
- **Export and durability** — whether the store can be dumped to plain files, which is
  the answer to "what if the daemon won't start."

## Out of scope

- Multi-user in any form: authentication, permissions, visibility rules, user entities.
  Ruled out at charting — see [Scope and shape settled at charting](tickets/T00-scope-and-shape.md).
  Assignee stays free text. If tikka ever goes multi-user it is a different product with
  a different data model, and a fresh map.
- Hosted or remote deployment, and anything that implies a network boundary beyond
  localhost.
- The refused feature set, in full: configurable workflows, custom fields, sprints and
  boards, estimates, time tracking, notifications, dashboards, plugins, attachments,
  epics/versions/components, and a query language (JQL or otherwise).
- Curated issue-link types — "relates to", "duplicates", "clones", "causes". Derived
  mentions cover the need; curated links go stale.
