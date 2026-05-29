# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Scripts split by responsibility:
- `./scripts/start.sh --build` builds the jar; `./scripts/start.sh [run-flags] [path]` runs it.
  `start.sh` is a thin launcher that forwards args verbatim — it holds no flag knowledge.
- `./scripts/start.sh --help` shows the full flag list (delegates to the jar's picocli usage, so it
  requires a built jar; without one it prints a build-first warning).
- `./scripts/tests.sh [--unit|--e2e|--all]` runs the JUnit and/or bash end-to-end suites.
- `./scripts/benchmarks.sh --combinations|--combinations-q` runs the thread/queue tuning sweeps.

The CLI is parsed in Java (picocli, in `config/Cli.java`) — the single source of truth for flag
names, help text, and validation. README.md has the architecture overview.

Non-obvious precondition: `--build` is required after any `.java` change before `./scripts/tests.sh
--e2e` or a real run (the e2e harness and `start.sh` run the built jar, not the sources).
