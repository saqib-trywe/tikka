---
id: T05
title: Choose the web UI approach
type: grilling
status: open
assignee: null
blocked-by: [T01]
---

## Question

How is the web UI built?

The scope decision already made narrows this a lot: the UI is a **reading and
reviewing** surface — lists, filters, issue detail, a frontier/graph view, the event
timeline — plus light inline edits. It is explicitly not a form-heavy authoring tool.
A read-first UI with a handful of small mutations is the best possible case for
server-rendered HTML with progressive enhancement, and the worst possible justification
for an SPA build pipeline.

So the real question is whether anything about tikka defeats that default:

- Does the frontier/graph view need genuine client-side interactivity (pan, zoom,
  drag), or does a server-rendered diagram suffice?
- Does the UI need to update live as agents mutate state underneath the viewer? If yes,
  that implies a push channel and changes the answer.
- What is the honest toolchain cost of each option for a solo local tool, counting
  every build step you would have to keep working a year from now?

Read [Survey the Clojure ecosystem for tikka's four surfaces](T01-clojure-ecosystem-survey.md)
for the options and their maturity.
