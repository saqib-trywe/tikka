---
id: T10
title: Daemon lifecycle and repo-to-project binding
type: grilling
status: open
assignee: null
blocked-by: [T06]
---

## Question

A daemon owning the store solves the concurrency problem and creates an operational one:
something has to start it, and something has to decide which project a command means.

- **Starting.** Does the user start the daemon explicitly, does the CLI start it on
  demand when it finds nothing listening, or is it a launchd-style background service?
  On-demand start is the most pleasant and the most surprising when it goes wrong.
- **Port.** Fixed default, or discovered via a file under `~/.tikka/`? Fixed is simpler
  until two things want it.
- **Repo binding.** A `.tikka` file in the repo naming the project key was the sketch.
  Is it committed (so it travels with the repo) or ignored (local preference)? What does
  the CLI do when run outside any bound directory — error, or fall back to a default
  project?
- **Bootstrapping a project**: how does one get created, and does binding a repo to a
  non-existent project create it or fail?
- **Store location and the single-store assumption.** One store under `~/.tikka/` was
  settled; confirm nothing wants a per-repo store, and decide what happens if two
  daemons are started.
- **Upgrades**: what happens to the store when the schema changes? Answering "nothing,
  it is local, I will delete it" is legitimate — but say so deliberately.

Depends on [Choose the CLI approach](T06-cli-approach.md), since the CLI is what most
often trips the lifecycle.
