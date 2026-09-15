---
id: T17
title: Decide export and durability
type: grilling
status: open
assignee: saqib
blocked-by: []
---

## Question

What is the answer to "the daemon won't start" and "I want my data out"?

Already true: the store is one SQLite file readable in `sqlite3` without tikka
([Choose the persistence engine](T03-persistence-engine.md)); migrations take a `VACUUM INTO` snapshot first
([Daemon lifecycle and repo-to-project binding](T10-daemon-lifecycle.md)).

- Is that enough, or does tikka need an export, and in what shape (JSON lines, markdown files per issue, both)?
- Is there an import, and does it contradict "no write carries its own timestamp"?
- Routine backups beyond migrations: scheduled snapshots, retention, or the user's own backup tooling?
- What does restore look like, and does the lock or schema version guard it?
