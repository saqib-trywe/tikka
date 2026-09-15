---
id: T15
title: Settle body and mention conventions
type: grilling
status: open
assignee: null
blocked-by: []
---

## Question

How is an issue id recognised in prose, and what does a mention do once it is found?

Settled around it: mentions are **derived**, never hand-authored; they are stored as edges and
rewritten in the same transaction as the body or comment that produced them
([Choose the persistence engine](T03-persistence-engine.md)); they may cross projects
([Define tikka's core invariants](T02-core-invariants.md)); they never carry an event onto the
mentioned issue ([Define the event log schema](T14-event-log-schema.md)); markdown is rendered by
Laika in the browser with raw HTML off ([Shape the Laminar frontend](T05-web-ui-approach.md)).
The walking skeleton deliberately leaves mention detection out.

- **Detection.** Which occurrences of `TIK-42` count? Inside code spans and fenced code blocks?
  Inside URLs (`/i/TIK-42`)? Adjacent to other characters (`TIK-42s`, `(TIK-42)`, `xTIK-42`)?
  Case-sensitive? Does detection parse markdown, or pattern-match raw text, and do the web UI
  and the daemon then agree on what counts?
- **Unknown ids.** A mention of `TIK-99` before TIK-99 exists, or of a project key that doesn't
  exist: stored and resolved later, ignored, or rejected? Issue numbers are never reused, so a
  forward reference can only ever mean one issue.
- **Rendering.** Do mentions become live links in the web UI, and does a link show the target's
  title and status? What does the CLI and MCP text show?
- **Titles.** Do mentions in a title count, or only bodies and comments?
- **Edits.** Editing a body removes a mention: the backlink disappears. Is that right when the
  timeline still shows the old text?
