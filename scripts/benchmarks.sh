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
                    Appends the tuning pick "path,producers,consumers,seconds" to scripts/benchmarks.dat.
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
REF_HASH=""             # hash of the first run; the reference every later run is compared against
LAST_DIFF_HASH=""       # most recent result that differed from REF_HASH; lets a persistent mid-sweep
                        # drift be told apart from a single flaky run
MISMATCHES=0
COUNT=0
# Distinct result hashes in first-seen order, with per-group run counts and first sighting. More than
# one group almost always means the scanned tree changed under us mid-sweep (files added/removed).
GROUP_ORDER=()
declare -A GROUP_COUNT GROUP_LABEL GROUP_FIRST

# Run one configuration. Args are the sweep flags (e.g. --producers=64 --consumers=48). --stats
# makes Main emit the "Run stats:" line we parse for peak threads / heap / cpu; the body hash
# (timing/stat lines stripped) confirms every config produced the same aggregation result.
run_one() {
    COUNT=$((COUNT + 1))
    local out sec peak mem cpu hash label
    out=$(java -jar "$JAR" "${JAVA_ARGS[@]}" "$@" --stats "$TARGET" 2>>"$ERRORS_TMP")
    sec=$(printf '%s' "$out"  | sed -nE 's/^Done in ([0-9.]+) s.*/\1/p')
    peak=$(printf '%s\n' "$out" | grep -oE 'peak=[0-9]+' | tail -1 | cut -d= -f2)
    mem=$(printf '%s' "$out"  | sed -nE 's/.*heap=([0-9]+)\/[0-9]+ MB.*/\1/p')
    cpu=$(printf '%s' "$out"  | sed -nE 's/.*cpu=([0-9]+)%.*/\1/p')
    hash=$(printf '%s\n' "$out" | grep -vE '^(Scanning |Done in |Run stats: |\[stat )' | sha256sum | cut -d' ' -f1)
    if [ -z "${GROUP_COUNT[$hash]:-}" ]; then
        GROUP_LABEL[$hash]=$(( ${#GROUP_ORDER[@]} + 1 ))
        GROUP_ORDER+=("$hash")
        GROUP_COUNT[$hash]=0
        GROUP_FIRST[$hash]="[$COUNT] ${*//--/}"
    fi
    GROUP_COUNT[$hash]=$(( GROUP_COUNT[$hash] + 1 ))
    label="${GROUP_LABEL[$hash]}"

    if [ -z "$REF_HASH" ]; then
        REF_HASH="$hash"
    elif [ "$hash" != "$REF_HASH" ]; then
        MISMATCHES=$((MISMATCHES + 1))
        if [ "$hash" = "$LAST_DIFF_HASH" ]; then
            printf '\n  !! result mismatch (matches earlier differing group %s): %s\n' "$label" "${*//--/}"
        else
            printf '\n  !! result mismatch (new differing group %s): %s\n' "$label" "${*//--/}"
            LAST_DIFF_HASH="$hash"
        fi
    fi
    printf '%s %s %s %s %s\n' "$sec" "$peak" "$mem" "$cpu" "${*//--/}" >> "$RESULTS_FILE"
    printf '\r[%2d/%2d] %-44s %6ss  peak=%-4s' "$COUNT" "$TOTAL" "${*//--/}" "$sec" "$peak"
}

# Format RESULTS_FILE rows (seconds threads mem cpu config...) into the aligned display columns.
fmt_rows() {
    awk '{s=$1;t=$2;m=$3;c=$4;$1=$2=$3=$4="";sub(/^ +/,"");printf "  %6.1f  %-7s  %-6s  %-5s  %s\n",s,t,m,c,$0}'
}

# Print verification and the 10 fastest configs on screen; --out (if given) gets the full table.
summarize() {
    echo; echo
    if [ "${#GROUP_ORDER[@]}" -le 1 ]; then
        echo "Result verification: all $TOTAL runs produced an identical result (sha256 $REF_HASH)."
    else
        echo "Result verification: $TOTAL runs produced ${#GROUP_ORDER[@]} DISTINCT results"
        echo "($MISMATCHES did not match group 1, the reference). Per-group run counts and first sighting:"
        local i=0 h
        for h in "${GROUP_ORDER[@]}"; do
            i=$((i + 1))
            printf '  group %s: %4d run(s), first seen %s  (sha256 %s)\n' \
                "$i" "${GROUP_COUNT[$h]}" "${GROUP_FIRST[$h]}" "$h"
        done
        echo "Distinct results usually mean the scanned tree changed mid-sweep (files added/removed);"
        echo "all timings are still ranked below, but cross-group wall times are not directly comparable."
    fi
    echo
    local header
    header=$(printf '  %6s  %-7s  %-6s  %-5s  %s' "s" "threads" "memMB" "cpu%" "config")
    echo "Top 10 fastest configurations:"
    echo "$header"
    sort -n "$RESULTS_FILE" | head -10 | fmt_rows
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

# Append one tuning recommendation for this run to scripts/benchmarks.dat as
# "path,producers,consumers,seconds" (seconds = the picked config's elapsed wall time).
# Among configs whose elapsed time is within 5% of the fastest, pick the one with the fewest total
# threads, breaking ties toward the faster run: single-run timings are noisy, so the band avoids
# chasing a fractional-second winner when a lighter pool performs essentially as well. The path is
# canonicalized so the file can later key per-tree defaults rather than a bare '.'.
append_optimal() {
    local dat="scripts/benchmarks.dat" pc p c sec abspath
    pc=$(awk '
        $1 ~ /^[0-9.]+$/ {
            sec=$1+0; p=0; c=0
            for (i=5;i<=NF;i++) {
                if ($i ~ /^producers=/)      { split($i,a,"="); p=a[2]+0 }
                else if ($i ~ /^consumers=/) { split($i,a,"="); c=a[2]+0 }
            }
            n++; S[n]=sec; RAW[n]=$1; P[n]=p; C[n]=c
            if (best=="" || sec<best) best=sec
        }
        END {
            if (n==0) exit 1
            thr=best*1.05; bt=-1
            for (i=1;i<=n;i++) if (S[i]<=thr) {
                tot=P[i]+C[i]
                if (bt<0 || tot<bt || (tot==bt && S[i]<bs)) { bt=tot; bp=P[i]; bc=C[i]; bs=S[i]; br=RAW[i] }
            }
            print bp, bc, br
        }
    ' "$RESULTS_FILE") || { echo "No timed runs to record in $dat." >&2; return; }
    read -r p c sec <<<"$pc"
    abspath=$(realpath "$TARGET" 2>/dev/null || printf '%s' "$TARGET")
    printf '%s,%s,%s,%s\n' "$abspath" "$p" "$c" "$sec" >> "$dat"
    echo "Tuning recommendation appended to $dat: $abspath,$p,$c,${sec}s"
}

echo
echo "NOTE: running a basic tuning aid, not a real benchmark — one run per config in a fresh JVM,"
echo "      sorted by wall time. Single runs are noisy; treat the ordering as a hint, not a measurement."
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

# Only the plain combinations sweep yields a producers/consumers pick; --combinations-q varies the
# queue too and is excluded from the tuning file.
if [ "$MODE" = "combinations" ]; then
    append_optimal
fi
