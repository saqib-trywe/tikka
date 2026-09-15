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

**Inherited from [Define the shared query and filter grammar](T08-query-grammar.md):**
an unscoped query defaults to the bound project. Decide what "bound" means where there is no
working directory — notably MCP clients — and what an unscoped query does then.

**Inherited from [Choose the persistence engine](T03-persistence-engine.md):**
the store is one SQLite file under `~/.tikka/`, opened in WAL mode with a single write connection,
so a second daemon on the same file would contend for the writer; decide how it is refused
(a lock file, or the port). Schema upgrades are yours; SQLite's `user_version` pragma makes a
migration runner cheap if you want one.

**Inherited from [Design the MCP tool contract](T07-mcp-tool-contract.md):**
an MCP connection is bound by its endpoint URL (`…/mcp?project=TIK`); the stdio proxy
(`tikka mcp`) reads the repo's binding and applies it. Unbound connections require `project` on
create and search all projects. Creating a project is a human act through the CLI or web, never MCP.

**Inherited from [Choose the MCP server implementation and transport](T04-mcp-implementation.md):**
decide what `tikka mcp` (the stdio proxy) does when no daemon is listening. MCP sessions on the
2025-11-25 lifecycle are in-memory and lost on restart (clients re-initialize), so restarts are
cheap for MCP. The daemon binds `127.0.0.1` only.

**Inherited from [Design the HTTP API shape](T09-http-api-shape.md):**
the CLI sends the binding as a `Tikka-Project` header and warns when `GET /api/meta` reports a
daemon version different from its own; decide whether that warning ever becomes an automatic
restart.
