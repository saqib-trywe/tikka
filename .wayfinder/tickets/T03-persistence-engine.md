---
id: T03
title: Choose the persistence engine
type: grilling
status: open
assignee: null
blocked-by: [T01]
---

## Question

Which store does the daemon own?

The requirements are not generic, and they are what should decide this:

- Runs **in-process** with zero external services.
- Expresses **graph queries**: hierarchy walks, and blocking closure for the frontier
  query (open + unblocked + unclaimed).
- Supports an **atomic compare-and-set** for `claim_issue`. This is load-bearing —
  preventing that exact race is why the daemon exists at all rather than plain files.
- Appends to the **event log in the same transaction** as the mutation it records.
- Survives process restart, and ideally is inspectable when the daemon will not start.

Read the resolution of
[Survey the Clojure ecosystem for tikka's four surfaces](T01-clojure-ecosystem-survey.md)
first for the landscape.
The decision is the tradeoff between query power and operational weight: a Datalog
store answers the graph queries natively but is a larger dependency and a less familiar
failure mode than SQLite, where the same queries are recursive CTEs.
