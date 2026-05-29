#!/usr/bin/env bash
set -euo pipefail
# A basic tuning aid — NOT a real benchmark. It runs the scan once per configuration in a fresh
# JVM, checks that every run produced an identical aggregation result, and sorts the configs by
# wall time so you can pick reasonable --producers/--consumers (and queue) values for a given tree.
# Single runs mean real noise: treat the ordering as a hint, not a measurement.
#
# Only the aggregate consumer is supported (its output is deterministic, so result equality is
# meaningful). All run flags belong to the jar (picocli) and are forwarded verbatim; this script
# owns only the benchmark-control flags below and the producer/consumer/queue values it sweeps.
cd "$(dirname "$0")/.."
JAR="target/folder-scanner-1.0-SNAPSHOT.jar"

MODE=""                 # combinations | combinations-q (required)
OUT_FILE=""             # write the full sorted results table here (benchmark-control, not a jar flag)
ERRORS_FILE=""          # write the deduplicated stderr from all runs here
TARGET=""
JAVA_ARGS=()            # other run flags (--exclude, --log-level, ...) forwarded to every run

usage() {
    cat <<EOF
A basic tuning aid (not a real benchmark) for the aggregate consumer: runs each config once and
sorts by wall time. Aggregate only, because the benchmark verifies every run produced an identical
result and that check is meaningful only for the deterministic aggregator (not the duplicate finder).

Usage: ./scripts/benchmarks.sh --combinations   [--out=FILE] [--errors=FILE] [run-flags] [path]
       ./scripts/benchmarks.sh --combinations-q [--out=FILE] [--errors=FILE] [run-flags] [path]

  --combinations    Sweep producers x consumers over {4,8,16,24,32,48,64,100} (64 runs), randomized.
  --combinations-q  Also sweep queue: {lbq,abq} x {4096,8192} x producers{100,64} x consumers{100,48}.
  --out=FILE        Write the full sorted results table to FILE (distinct from the jar's --out).
  --errors=FILE     Write the deduplicated stderr collected across all runs to FILE.
  run-flags         Any run flag (e.g. --exclude=.git,node_modules) forwarded to every run.
                    --consumer=duplicates is rejected.
  path              Directory to scan (default: current directory).

Build the jar first:  ./scripts/start.sh --build
EOF
}

for arg in "$@"; do
    case "$arg" in
        --combinations)   MODE="combinations" ;;
        --combinations-q) MODE="combinations-q" ;;
        --out=*)          OUT_FILE="${arg#--out=}" ;;
        --errors=*)       ERRORS_FILE="${arg#--errors=}" ;;
        -h|--help)        usage; exit 0 ;;
        --consumer=duplicates)
            echo "Error: benchmarks only support --consumer=aggregate (result equality is" >&2
            echo "       meaningful only for the deterministic aggregator)." >&2
            exit 2 ;;
        -*)               JAVA_ARGS+=("$arg") ;;
        *)                TARGET="$arg" ;;
    esac
done

[ -n "$MODE" ] || { echo "Specify --combinations or --combinations-q (see --help)." >&2; exit 2; }
if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    echo "Build it first:  ./scripts/start.sh --build" >&2
    exit 2
fi
[ -n "$TARGET" ] || TARGET="."

RESULTS_FILE=$(mktemp)
ERRORS_TMP=$(mktemp)     # every run's stderr; deduped to $ERRORS_FILE at the end
trap 'rm -f "$RESULTS_FILE" "$ERRORS_TMP"' EXIT
REF_HASH=""
MISMATCHES=0
COUNT=0

# Run one configuration. Args are the sweep flags (e.g. --producers=64 --consumers=48). --stats
# makes Main emit the "Run stats:" line we parse for peak threads / heap / cpu; the body hash
# (timing/stat lines stripped) confirms every config produced the same aggregation result.
run_one() {
    COUNT=$((COUNT + 1))
    local out sec peak mem cpu hash desc
    out=$(java -jar "$JAR" "${JAVA_ARGS[@]}" "$@" --stats "$TARGET" 2>>"$ERRORS_TMP")
    sec=$(printf '%s' "$out"  | sed -nE 's/^Done in ([0-9.]+) s.*/\1/p')
    peak=$(printf '%s\n' "$out" | grep -oE 'peak=[0-9]+' | tail -1 | cut -d= -f2)
    mem=$(printf '%s' "$out"  | sed -nE 's/.*heap=([0-9]+)\/[0-9]+ MB.*/\1/p')
    cpu=$(printf '%s' "$out"  | sed -nE 's/.*cpu=([0-9]+)%.*/\1/p')
    hash=$(printf '%s\n' "$out" | grep -vE '^(Scanning |Done in |Run stats: |\[stat )' | sha256sum | cut -d' ' -f1)
    if [ -z "$REF_HASH" ]; then
        REF_HASH="$hash"
    elif [ "$hash" != "$REF_HASH" ]; then
        MISMATCHES=$((MISMATCHES + 1))
        printf '\n  !! result mismatch: %s\n' "${*//--/}"
    fi
    printf '%s %s %s %s %s\n' "$sec" "$peak" "$mem" "$cpu" "${*//--/}" >> "$RESULTS_FILE"
    printf '\r[%2d/%2d] %-44s %6ss  peak=%-4s' "$COUNT" "$TOTAL" "${*//--/}" "$sec" "$peak"
}

# Format RESULTS_FILE rows (seconds threads mem cpu config...) into the aligned display columns.
fmt_rows() {
    awk '{s=$1;t=$2;m=$3;c=$4;$1=$2=$3=$4="";sub(/^ +/,"");printf "  %6.1f  %-7s  %-6s  %-5s  %s\n",s,t,m,c,$0}'
}

# Print verification and the 5 fastest configs on screen; --out (if given) gets the full table.
summarize() {
    echo; echo
    if [ "$MISMATCHES" -eq 0 ]; then
        echo "Result verification: all $TOTAL runs produced an identical result (sha256 $REF_HASH)."
    else
        echo "Result verification: $MISMATCHES of $TOTAL runs did NOT match (see warnings above)."
    fi
    echo
    local header
    header=$(printf '  %6s  %-7s  %-6s  %-5s  %s' "s" "threads" "memMB" "cpu%" "config")
    echo "Top 5 fastest configurations:"
    echo "$header"
    sort -n "$RESULTS_FILE" | head -5 | fmt_rows
    if [ -n "$OUT_FILE" ]; then
        {
            echo "# $TOTAL runs on $TARGET  (sha256 $REF_HASH, $([ "$MISMATCHES" -eq 0 ] && echo identical || echo "$MISMATCHES mismatches"))"
            echo "# All configurations, sorted ascending by seconds (fastest first):"
            echo "$header"
            sort -n "$RESULTS_FILE" | fmt_rows
        } > "$OUT_FILE"
        echo; echo "All $TOTAL results written to $OUT_FILE"
    fi
    if [ -n "$ERRORS_FILE" ]; then
        sort -u "$ERRORS_TMP" > "$ERRORS_FILE"   # dedup: a skip that fires once per run appears once
        echo "Unique stderr lines across all runs: $(wc -l < "$ERRORS_FILE") -> $ERRORS_FILE"
    fi
}

echo "NOTE: basic tuning aid, not a real benchmark — one run per config in a fresh JVM, sorted"
echo "      by wall time. Single runs are noisy; treat the ordering as a hint, not a measurement."
echo

# Warm up the FS cache once so the first measured run isn't disproportionately slow.
java -jar "$JAR" "${JAVA_ARGS[@]}" --producers=8 --consumers=8 "$TARGET" >/dev/null 2>&1 || true

if [ "$MODE" = "combinations-q" ]; then
    QT=(lbq abq); QS=(4096 8192); P=(100 64); C=(100 48)
    TOTAL=$(( ${#QT[@]} * ${#QS[@]} * ${#P[@]} * ${#C[@]} ))
    echo "Running $TOTAL queue/size/producers/consumers combinations on $TARGET..."
    for qt in "${QT[@]}"; do for qs in "${QS[@]}"; do for p in "${P[@]}"; do for c in "${C[@]}"; do
        run_one --queue-type=$qt --queue-size=$qs --producers=$p --consumers=$c
    done; done; done; done
else
    V=(4 8 16 24 32 48 64 100)
    TOTAL=$(( ${#V[@]} * ${#V[@]} ))
    echo "Running $TOTAL producer/consumer combinations on $TARGET (randomized order)..."
    # Randomize the (producers, consumers) order so filesystem-cache warmth doesn't bias one config.
    # Process substitution (not a pipe) keeps the loop in this shell so run_one's counters persist.
    while read -r p c; do
        run_one --producers=$p --consumers=$c
    done < <(for p in "${V[@]}"; do for c in "${V[@]}"; do echo "$p $c"; done; done | shuf)
fi

summarize
