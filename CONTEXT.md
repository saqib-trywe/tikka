# Context

Domain vocabulary for tikka. A glossary, not a spec: no implementation details, no
decisions. Decisions live on [the map](.wayfinder/MAP.md); architecture lives in ADRs.

## Project

A namespace for issues. Owns a short key (`TIK`) and the sequence that numbers them.
Projects do not nest and carry no configuration — they exist to scope ids and nothing
else.

## Issue

The unit of work, and the only substantial entity in tikka. An issue has a title, a
markdown body, a status, labels, an assignee, an optional parent, blocking edges,
comments, and timestamps.

There are no issue *types* — no epic, story, bug, or task. Labels carry every semantic
distinction, which keeps tikka ignorant of the vocabulary of whatever is using it.

### Issue id

A project key plus a monotonic integer: `TIK-42`. Assigned once, never reused, never
changed. Chosen to be speakable aloud and typeable into a command line.

## Status

Exactly two values: `open` and `closed`. Nothing else is a status.

**In progress** is not a status. It is *derived*: an open issue with an assignee is in
progress. This is a deliberate refusal — a stored in-progress flag can drift out of
agreement with the assignee, and a derived one cannot.

## Resolution

Recorded when an issue is closed. Either `done` (the work was carried out) or `dropped`
(the issue was closed without being carried out — abandoned, obsolete, or ruled outside
the scope of the effort).

The distinction matters because "we finished this" and "we decided not to" are different
facts about the route taken, and a single closed status loses the difference.

## Assignee

Free text naming whoever or whatever holds the issue. Not a reference to a user entity —
tikka has no users. An agent session and a human are the same kind of thing here.

## Claim

Taking an issue by setting its assignee, conditional on it having none. A claim
**fails** rather than overwrites when the issue is already assigned, which is what makes
it safe for concurrent sessions to race for the same work.

An open issue with no assignee is **unclaimed**.

## Edges

Exactly three kinds of relationship between issues.

### Parent

Structural hierarchy. An issue has at most one parent and any number of **children**.
Hand-set. Carries containment, not sequencing — a parent is not blocked by its children.

### Blocks

An issue **blocks** another when the second cannot proceed until the first is closed.
Hand-set, many-to-many, and acyclic: writes that would form a cycle are rejected.

An issue is **blocked** while any issue blocking it is still open, and **unblocked**
once all of them are closed.

### Mention

A reference to an issue id occurring in a body or comment. Mentions are **derived**, not
curated: writing `TIK-42` in prose creates the edge, and the mentioned issue gains a
**backlink**. There is no way to hand-author or hand-remove a mention.

This is the whole of tikka's "see also". Curated link types — *relates to*, *duplicates*,
*clones* — are deliberately absent; they go stale because nothing forces them to stay
true, whereas a derived edge cannot disagree with the text that produced it.

## Label

An opaque string attached to an issue. Tikka attaches no meaning to any label and
interprets none of them, including namespaced ones like `wayfinder:map`. Meaning belongs
to whoever is reading.

## Rank

The position of an issue in a total order. Defaults to the issue's sequence number, so
order is deterministic without anyone setting it, and can be overwritten to reorder.

Rank is not priority. Tikka has no priority.

## Event

A record that something about an issue changed: who, when, which field, and from what to
what. Events accumulate and are never edited or removed, forming an issue's **timeline**.

An issue's current state is the authority on what is true now; the timeline is the record
of how it got there. They are separate on purpose.

## Frontier

The set of issues that are open, unblocked, and unclaimed — the work takeable right now.

> **Open question.** Frontier is currently the vocabulary of a *consumer* of tikka rather
> than of tikka itself. Whether it becomes a tikka term — a named predicate in the query
> grammar — is unsettled; see
> [Define the shared query and filter grammar](.wayfinder/tickets/T08-query-grammar.md).
> If it stays a consumer's word, this entry moves out of the glossary.
