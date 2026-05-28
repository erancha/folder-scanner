# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Entry points: `./scripts/start.sh --help` for runtime flags, `./scripts/start.sh --build|--test|--test=unit|--test=e2e` for build and tests. README.md has the architecture overview.

Non-obvious precondition: `--build` is required after any `.java` change before `--test=e2e` or a real run (the e2e harness runs the built jar, not the sources).
