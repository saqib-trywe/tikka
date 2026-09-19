# tikka

Work tracking for people who work with agents. Local-only, no accounts, no network, and simpler than JIRA on
purpose: issues, parents, blockers, labels, claims and comments, and nothing else.

An agent reads and writes the same issues you do, through the same core. It claims work before starting, records
what it changed, and leaves an event log you can read afterwards. Everything lives in one SQLite file on your
machine.

```sh
tikka search ready                      # what could be worked on now
tikka claim TIK-42 --as saqib           # take it
tikka close TIK-42 done -m "shipped"    # finish it
```

Status: **0.1.0**, macOS and Linux. One daemon, four surfaces over one core — CLI, web UI, HTTP API, MCP.

## Install

Needs a JDK 21 or newer, [sbt](https://www.scala-sbt.org), and a C toolchain to link the CLI. The CLI links
`libcurl` and `libidn2`, so on macOS `brew install libidn2` first; on Debian or Ubuntu,
`apt install libcurl4-openssl-dev libidn2-dev`.

```sh
git clone https://github.com/saqib-trywe/tikka && cd tikka
scripts/install.sh              # builds, then installs to ~/.local/bin/tikka and ~/.tikka/lib
tikka daemon install            # a service that starts at login (launchd or systemd)
```

There is no packaged release: the install builds from source, which is a deliberate choice for a tool used by the
person who builds it.

## Use it

```sh
cd your-repo
tikka init TIK --new "Your project"   # creates the project and writes .tikka
tikka new "Make the thing work"
tikka search ready
```

The web UI is the same daemon: <http://127.0.0.1:7017/>. Every view is a URL — a search, an issue, a project's
tree — so anything you are looking at is a link you can paste to an agent.

`tikka --help` lists the rest: `show`, `edit` (opens `$EDITOR` with no flags), `claim`, `release`, `reassign`,
`close`, `reopen`, `project`, and `daemon` for `status`, `logs`, `restart`, `export` and `restore`.

## Agents

Point an MCP client at the daemon. In a repository with a `.tikka` binding, `.mcp.json`:

```json
{
  "mcpServers": {
    "tikka": { "type": "http", "url": "http://127.0.0.1:7017/mcp?project=TIK" }
  }
}
```

Clients that only spawn stdio servers get `tikka mcp` instead, which forwards stdin to the same endpoint.

Nine tools: `search_issues`, `get_issue`, `create_issue`, `update_issue`, `claim_issue`, `release_issue`,
`reassign_issue`, `close_issue`, `reopen_issue`. Both the 2025-11-25 and 2026-07-28 protocol revisions are served,
and a conformance suite runs against a real daemon in CI.

What makes this workable for agents rather than merely possible: a claim is exclusive, so two sessions cannot hold
the same issue; every write takes a comment saying why; and rejections come back as reasons, not stack traces.

## Queries

One grammar, shared by the CLI, the API, MCP and the web UI, so a query you type is a query an agent can send.

```
ready                                open, unclaimed, unblocked, nothing above it unfinished
label:bug -assignee:none             tagged bug and held by someone
project:TIK parent:none              the roots of a project's tree
under:TIK-1 status:open              open work anywhere beneath an issue
text:"index corrupted"               full-text over titles, bodies and comments
mentions:TIK-42                      issues whose prose refers to TIK-42
updated-after:1w sort:updated-desc   touched in the last week, most recent first
```

Filters: `ready`, `blocked`, `unblocked`, `project`, `status`, `resolution`, `assignee`, `label`, `parent`,
`under`, `blocks`, `blocked-by`, `mentions`, `mentioned-by`, `id`, `text`, and `created`, `updated`, `closed` and
`claimed`, each with an `-after` or `-before` suffix taking `30m`, `2h`, `3d`, `1w` or a date. Most take a
comma-separated list, and any of them can be negated with `-`.

Sort by `rank` (the default), `created`, `updated` or `closed`, each with an optional `-asc` or `-desc`. Results
page with a cursor rather than an offset, so nothing is skipped or repeated when the list changes underneath.

## Where things live

| | |
| --- | --- |
| `~/.tikka` | the home; `TIKKA_HOME` overrides it |
| `~/.tikka/tikka.db` | the store, one SQLite file, with WAL and full durability |
| `~/.tikka/config.toml` | `port = 7017` by default, fixed rather than discovered so MCP configs can hardcode it |
| `~/.tikka/backups` | a snapshot taken before every migration |
| `.tikka` | in a repository: the project its commands mean, found by walking up like `.git` |

`tikka daemon export` writes the whole store as JSON Lines, with the daemon running or not, and `restore` puts one
back. Nothing is locked inside the database format.

## Reading further

- [CONTEXT.md](CONTEXT.md) — the vocabulary. Every domain word means exactly one thing here; read it before using
  one.
- [docs/adr](docs/adr) — the decisions that were expensive to make and would be expensive to revisit.
- [.wayfinder](.wayfinder/MAP.md) — how the thing was planned, one closed ticket per decision. The tickets are the
  spec; [docs/build-plan.md](docs/build-plan.md) only orders the build.
- [CLAUDE.md](CLAUDE.md) — conventions, for agents and people alike.

## Building

```sh
sbt "core/test; daemon/test; cliJVM/test; integration/test"
sbt "sharedJS/testFull; ui/testFull; ui/fullLinkJS"    # the browser side
sbt cliRelease                                         # the release CLI binary
sbt daemon/assembly                                    # the daemon jar, web UI included
```

CI runs the same on Linux and macOS, links the CLI natively on both, holds `tikka search` against a live daemon to
a 100 ms budget, and stresses the CLI over hundreds of runs because the fault that cost the most here showed up
about once in two hundred.
