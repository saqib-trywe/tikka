---
id: T02
title: Define tikka's core invariants
type: grilling
status: open
assignee: null
blocked-by: []
---

## Question

The domain model is settled — two entities, three edge kinds, two statuses. What are
the *rules* that model must enforce? These are the invariants every surface inherits,
so getting them wrong is expensive and getting them late means four surfaces disagree.

Specifically:

- **Closure and rollup.** Does closing a parent close its children? Can a child outlive
  a closed parent? Does closing a parent with open children require a force flag, warn,
  or silently allow it?
- **Blocking versus closure.** Can an issue be closed while it still has open blockers?
  Does a *closed* issue continue to block its dependents, or does closing it release
  them? (The second is almost certainly right, but it needs stating, because the
  frontier query depends entirely on it.)
- **Cycle rejection.** Blocking is a DAG, so writes that would create a cycle are
  rejected. At what depth is this checked, and what does the caller see? Does `parent`
  need the same protection — can hierarchy cycle?
- **Claim semantics.** `claim_issue` is compare-and-set: it fails if already assigned.
  Can a claim be stolen or force-reassigned? Does closing release the claim? Do claims
  expire, given an agent session can die holding one — and if they don't, what unwedges
  a ticket claimed by a session that never came back?
- **Deletion.** Is deletion possible at all, or is `dropped` the only exit? If an issue
  can be deleted, what happens to edges pointing at it and to mentions of its id?

Call `grilling` and `domain-modeling`. Record resolved terms in `CONTEXT.md`.
