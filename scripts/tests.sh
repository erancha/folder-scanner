#!/usr/bin/env bash
# Mode dispatcher for the folder-scanner test suites: runs the JUnit unit suite (mvn), the bash
# end-to-end suite (scripts/tests-e2e.sh), or both, per the mode flag parsed below. The e2e
# assertions live in the delegate, not here.
set -uo pipefail
# Run from the repo root so the mvn and scripts/tests-e2e.sh invocations below resolve.
cd "$(dirname "$0")/.."

usage() {
    cat <<EOF
Usage: ./scripts/tests.sh [--unit | --e2e | --all]

  --unit   Run the JUnit unit suite only (no jar needed).
  --e2e    Run the bash end-to-end suite only (scripts/tests-e2e.sh). Requires the
           jar; build it with ./scripts/start.sh --build (this runner does NOT rebuild it).
  --all    Unit suite then e2e suite (default).

Sequential; no parallelism by design.
EOF
}

MODE="all"
for arg in "$@"; do
    case "$arg" in
        --unit) MODE="unit" ;;
        --e2e)  MODE="e2e" ;;
        --all)  MODE="all" ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $arg (see ./scripts/tests.sh --help)" >&2; exit 2 ;;
    esac
done

# Unit phase (JUnit, no jar needed). A failure here aborts before the slower e2e suite runs.
if [ "$MODE" = "unit" ] || [ "$MODE" = "all" ]; then
    mvn test || exit $?
    [ "$MODE" = "unit" ] && exit 0
fi

exec scripts/tests-e2e.sh
