---
id: T03
title: Choose the persistence engine
type: grilling
status: open
assignee: null
blocked-by: [T13]
---

## Question

Which store does the daemon own, and which Scala library reaches it?

The requirements are specific, and should decide this rather than taste:

- Runs **in-process** with zero external services. This alone disqualifies most of the
  Typelevel ecosystem's default answers, which assume Postgres.
- Expresses **recursive queries**: hierarchy walks, and transitive closure over blocking
  edges for the frontier query (open + unblocked + unclaimed).
- Supports an **atomic compare-and-set** for `claim_issue`. Load-bearing — preventing
  that exact race is why the daemon exists rather than plain files. In SQL this is
  `UPDATE … WHERE assignee IS NULL` and checking the affected-row count.
- Appends to the **event log in the same transaction** as the mutation it records.
- Survives restart, and ideally is inspectable when the daemon will not start.

Read [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md)
first.

SQLite is the obvious embedded answer; the real question is the layer above it. doobie is
the Typelevel-native choice and composes with cats-effect transactions directly. Magnum
is the house skill's preference but is direct-style, so it fits tikka's stack poorly —
note this is a case where the overridden skill's advice genuinely does not transfer.
Weigh also: does anything here need a typed query DSL at all, or are hand-written SQL and
recursive CTEs the honest answer for a schema this small?
