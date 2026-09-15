---
id: T06
title: Choose the CLI approach
type: grilling
status: open
assignee: null
blocked-by: [T13]
---

## Question

How is the CLI built and distributed?

The CLI is a thin HTTP client to the daemon with full CRUD, so its logic is trivial. The
decision is **startup time**: a CLI that takes a second to list issues will not get used.
The JVM startup floor is disqualifying on its own, so the CLI cannot be a plain JVM
process — this much survived from the Clojure survey.

- What is the startup budget? Sub-100ms is the bar for a tool that feels instant.
- Which approach hits it: **Scala Native**, **GraalVM native-image**, or something else?
  Read [Survey the Scala ecosystem for tikka's four surfaces](T13-scala-ecosystem-survey.md)
  for measured figures and for whether cats-effect and the chosen HTTP client actually
  work on each.
- **Does the CLI use the Typelevel stack at all?** Initialising a cats-effect runtime
  costs startup time, and a native target may constrain which libraries load. A CLI that
  makes one HTTP call and prints may be better served by a minimal client with no effect
  system — which is legitimate, but it means the CLI shares less of the core. Decide how
  much sharing is worth how many milliseconds.
- **Shared code.** Domain types and codecs cross-compiled to the native target, or a
  small duplicated client? Duplication is the honest answer if cross-building costs more
  than the code it saves.
- **Output shape.** Human-readable tables by default with a machine flag for scripting,
  or structured always?
- **Build cost.** Native builds are slow and have their own failure modes; what does that
  do to the edit-run loop while developing the CLI?

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
queries are a positional argument in the shared syntax. Decide whether the CLI parses
locally (the parser must then build for the native target) or sends the raw string and lets
the daemon reject it — the latter keeps the CLI thinner at the cost of a round trip for typos.
