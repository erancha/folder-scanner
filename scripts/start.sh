#!/usr/bin/env bash
set -euo pipefail
# Resolve project root (this script lives in scripts/) so relative paths anchor to the repo
cd "$(dirname "$0")/.."

# Glob the shaded jar rather than pin a version: the POM is the single version source.
JAR=$(echo target/folder-scanner-*.jar)

# Every run flag is owned by the jar (picocli is the single source of truth). This launcher forwards args verbatim 
# and holds NO flag knowledge, so a typo here can never silently drop a flag. Full flag list: build, 
# then `java -jar "$JAR" --help`. Tests live in scripts/tests.sh, benchmarks in scripts/benchmarks.sh. 
# --build and --mermaid below are launcher commands, not jar arguments, so they are not duplicated in Java.
case "${1:-}" in
    --build)
        # Always clean: a stale jar from a prior version must never shadow new code.
        exec mvn clean package ;;
    --mermaid)
        # Undocumented dev helper: render the README's mermaid block to data-flow.png via mermaid-cli.
        tmp="$(mktemp --suffix=.mmd)"
        trap 'rm -f "$tmp"' EXIT
        awk '/^```mermaid$/{f=1;next}/^```$/{f=0}f' README.md > "$tmp"
        npx -y -p @mermaid-js/mermaid-cli mmdc -i "$tmp" -o data-flow.png
        exit ;;
esac

# Every other invocation runs the jar. Fail loudly (rather than silently building) if it is
# missing: --help and all flags live in the jar, so without it the only useful action is to build.
if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    echo "Build it first:  ./scripts/start.sh --build" >&2
    exit 2
fi

exec java -jar "$JAR" "$@"
