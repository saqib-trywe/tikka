---
id: T05
title: Shape the Laminar frontend
type: grilling
status: open
assignee: null
blocked-by: [T13]
---

## Question

Laminar is decided — see [Adopt Scala with the Typelevel stack](T12-adopt-scala.md). The
open question is how the frontend is structured around it.

The UI's job is already fixed: a **reading and reviewing** surface — lists, filters,
issue detail, a ready/graph view, the event timeline — plus light inline edits.
Explicitly not a form-heavy authoring tool. Every structural choice below should be
judged against that narrow job, not against what a general-purpose SPA would want.

- **Shared code.** Which modules cross-compile between JVM and Scala.js — domain types
  only, or also the query/filter grammar and validation? This is the reason Laminar was
  chosen, so it should be decided deliberately rather than grown.
- **API client.** Hand-written fetch calls against the HTTP API, or endpoint definitions
  shared with the server (e.g. via tapir) generating a typed client? The latter makes
  server/client drift a compile error, at the cost of pulling tapir into an http4s stack.
- **JSON codec** that cross-compiles, and its bundle-size cost on Scala.js.
- **Build toolchain.** sbt, scala-cli or mill, plus Vite for dev serving — and how the
  daemon serves the built assets in production. Count every build step you would still
  have to keep working a year from now.
- **Live updates.** Does the UI update as agents mutate state underneath the viewer? If
  yes, that means a push channel (SSE over http4s via fs2), which changes the API design.
- **Graph view.** Does the ready/graph view need a JS diagramming library via
  Scala.js facades, or does a Laminar-rendered ordered list carry it?
- **Routing** within the SPA, and whether issue URLs (`/i/TIK-42`) are served by the
  daemon directly so links from agents and CLI output open correctly.

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
the UI is a search box over the shared syntax, with controls editing the string and the URL
carrying it; the query parser must cross-compile to Scala.js so the UI validates locally;
paging is "load more" over cursors.
