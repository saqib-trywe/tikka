# tikka

An agent-first, local-only work management system. Simpler than JIRA. Scala 3.

Planning lives in [.wayfinder/MAP.md](.wayfinder/MAP.md). The build follows [docs/build-plan.md](docs/build-plan.md). Domain vocabulary lives in
[CONTEXT.md](CONTEXT.md) — read it before using any domain term.

## Stack

Scala 3 with the **Typelevel** stack — cats-effect, http4s, fs2, circe — and **Laminar**
for the web UI.

## The direct-style-scala skill is overridden here

That skill auto-loads for Scala tasks and mandates direct-style Ox + Tapir, stating
`.serverLogic` MUST NOT be used. **Ignore that guidance in this repository.** Tikka uses
cats-effect `IO` deliberately; see
[ADR 0001](docs/adr/0001-scala-with-typelevel-stack.md) for the reasoning and the costs
accepted.

Do not "correct" cats-effect wiring to `.handle`, do not propose Ox, and do not re-open
the question. Its general Scala advice — explicit return types, opaque types over raw
primitives, ADTs for errors, no class-level `var`, braceless syntax — still applies and
is good practice regardless of effect system.

## Conventions

- Explicit return types on every public definition.
- Opaque types for domain identifiers and quantities; never raw `String`/`Int`.
- Sealed/enum error hierarchies; no stringly-typed errors.
- Model distinct entity states as distinct types rather than `Option` fields.
