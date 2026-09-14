---
id: T06
title: Choose the CLI approach
type: grilling
status: open
assignee: null
blocked-by: [T01]
---

## Question

How is the CLI built and distributed?

The CLI is a thin HTTP client to the daemon with full CRUD, so its logic is trivial.
The entire decision is **startup time**: a command-line client that takes a second to
print a list of issues will not get used, and JVM startup is the classic Clojure
failure here.

- What is the startup budget? (Sub-100ms is the bar for a tool that feels instant.)
- Which approach hits it — a Babashka script, a GraalVM native-image binary, or a JVM
  uberjar with a persistent process?
- What does each cost in build complexity and in library compatibility, given the CLI
  needs little more than HTTP and EDN/JSON?
- Does the CLI share code with the daemon, or is duplicating a small client the honest
  cheaper answer?
- Output shape: human-readable tables by default with a `--json`-style flag for
  scripting, or structured always?

Read [Survey the Clojure ecosystem for tikka's four surfaces](T01-clojure-ecosystem-survey.md)
for measured startup figures.
