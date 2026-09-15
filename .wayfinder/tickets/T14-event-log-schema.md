---
id: T14
title: Define the event log schema
type: grilling
status: open
assignee: null
blocked-by: [T03]
---

## Question

What does an event record, and how does a write become events?

The glossary fixes the concept: who, when, which field, from what to what; append-only;
separate from current state. The persistence engine fixes the mechanics: SQLite, events written
in the same transaction as the mutation, timestamps from the daemon clock, and an issue's
**updated** time defined as the time of its latest event. What is still open:

- **Granularity.** One event per field changed, or one event per write carrying every field it
  changed? An agent's single `update_issue` touching title and labels: one timeline entry or two?
- **Which issues an event lands on.** `TIK-3 blocks TIK-7` changes both issues' state. One event
  on each, or one event referenced by both? This decides whether adding a blocker moves both
  issues' updated time.
- **Who.** Assignee is free text and there is no auth. What goes in the actor field: the
  agent's self-declared name, the surface (`mcp`, `cli`, `web`), both? Can it be absent?
- **Payload shape.** Typed columns per event kind, or a kind plus a JSON payload? The store must
  stay readable in `sqlite3` without tikka, which the persistence engine settled.
- **Prose changes.** Does editing a body record the full before and after text, a diff, or only
  that it changed? Mentions are rewritten in the same transaction: do added and removed mentions
  appear as events of their own?
- **Derived changes.** Closing a blocker unblocks its dependents, and blocked is never stored.
  Does the dependent's timeline show "unblocked"? If so, that event is derived, which fits
  awkwardly with a log of writes.
- **Reading it back.** What the web UI timeline and an agent's history query need from it,
  and whether events are ever queried across issues ("what changed in the last hour").

Read [Choose the persistence engine](T03-persistence-engine.md) and
[Define tikka's core invariants](T02-core-invariants.md) first.
