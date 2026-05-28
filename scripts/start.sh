#!/usr/bin/env bash
set -euo pipefail
# Resolve project root (this script lives in scripts/), then cd there so every relative
# path below — JAR, target, sibling scripts, default scan target — stays anchored to the
# repo layout regardless of where the caller invoked us from.
cd "$(dirname "$0")/.."

FLAGS=""
TARGET=""
COMBINATIONS=0
COMBINATIONS_Q=0
BUILD=0              # --build runs `mvn package` to produce the executable jar, then exits
TEST_MODE=""         # --test[=unit|e2e|all] dispatches to JUnit, bash e2e harness, or both
OUT_FILE=""
ERRORS_FILE=""          # combinations modes only: collects stderr (skip messages) across all runs
QUEUE_TYPE_FLAG=""      # passed to combinations mode too so the combinations mode uses the chosen impl
QUEUE_SIZE_FLAG=""      # passed to combinations mode too so the combinations mode uses the chosen capacity
CONSUMER_FLAG=""        # -Dconsumer=... ; defaults to aggregate when unset
HARD_DELETE=0           # 1 when --hard-delete was passed
MIN_SIZE_RAW=""         # raw value of --min-size=... ; forwarded to Java, parsed there
EXCLUDE_RAW=""          # raw value of --exclude=... ; comma-separated dir basenames, forwarded to Java
FILE_EXTENSIONS_RAW=""  # raw value of --file-extensions=... ; forwarded to Java as -Dfileextensions
OUT_FLAG_RAW=""         # raw value of --out=... ; bash uses it for tee in aggregate mode,
                        # Java uses it for the script path in duplicates mode
JAR="target/folder-scanner-1.0-SNAPSHOT.jar"  # produced by `./scripts/start.sh --build`; consumed by every run path below
for arg in "$@"; do
    case "$arg" in
        --stats)
            FLAGS="$FLAGS -Dstats=true" ;;
        --log-level=*)
            FLAGS="$FLAGS -Dlog.level=${arg#--log-level=}" ;;
        --producers=*)
            FLAGS="$FLAGS -Dproducers=${arg#--producers=}" ;;
        --consumers=*)
            FLAGS="$FLAGS -Dconsumers=${arg#--consumers=}" ;;
        --queue-type=*)
            QUEUE_TYPE_FLAG="-Dqueuetype=${arg#--queue-type=}"
            FLAGS="$FLAGS $QUEUE_TYPE_FLAG" ;;
        --queue-size=*)
            QUEUE_SIZE_FLAG="-Dqueuesize=${arg#--queue-size=}"
            FLAGS="$FLAGS $QUEUE_SIZE_FLAG" ;;
        --combinations)
            COMBINATIONS=1 ;;
        --combinations-q)
            COMBINATIONS_Q=1 ;;
        --consumer=*)
            val="${arg#--consumer=}"
            case "$val" in
                aggregate|duplicates) CONSUMER_FLAG="-Dconsumer=$val" ;;
                *) echo "Unknown --consumer value: $val (expected aggregate or duplicates)" >&2
                   exit 2 ;;
            esac ;;
        --build)
            BUILD=1 ;;
        --test)
            TEST_MODE="all" ;;
        --test=*)
            TEST_MODE="${arg#--test=}" ;;
        --hard-delete)
            HARD_DELETE=1 ;;
        --min-size=*)
            MIN_SIZE_RAW="${arg#--min-size=}" ;;
        --file-extensions=*)
            FILE_EXTENSIONS_RAW="${arg#--file-extensions=}" ;;
        --exclude=*)
            EXCLUDE_RAW="${arg#--exclude=}" ;;
        --out=*)
            OUT_FILE="${arg#--out=}"
            OUT_FLAG_RAW="$OUT_FILE" ;;
        --errors=*)
            ERRORS_FILE="${arg#--errors=}" ;;
        -h|--help)
            cat <<EOF
Usage: ./scripts/start.sh --build
       ./scripts/start.sh --test
       ./scripts/start.sh [--consumer=aggregate|duplicates] [--stats] [--producers=N] [--consumers=N]
                  [--queue-type=lbq|abq] [--queue-size=N] [--out=PATH] [--hard-delete] [--min-size=SIZE]
                  [--file-extensions=LIST] [--exclude=NAME1,NAME2,...] [path]
       ./scripts/start.sh --combinations   [--queue-type=lbq|abq] [--queue-size=N] [--out=FILE] [--errors=FILE] [path]
       ./scripts/start.sh --combinations-q [--out=FILE] [--errors=FILE] [path]

  --build         Run \`mvn -q clean package\` to (re)build the executable jar at \$JAR, then exit. All other
                  run modes load classes from this jar, so run this once after changing any .java file.
  --test[=MODE]   Run the test suite and exit. MODE is unit (JUnit only, no jar needed), e2e (bash
                  harness only, jar required), or all (default when --test has no value: unit then e2e).
                  Unit covers the pure helpers (Format, SizeBucket, SoftDeletePathEncoder, OutPathResolver,
                  ScriptWriter.shellQuote, Aggregator's extensionOf / classifyByDate). E2e drives
                  scripts/start.sh end-to-end against a fixture tree built under \$TMPDIR, including
                  the scripts/start.sh error-path branches the JUnit suite cannot reach. Does NOT
                  rebuild the jar; pair with --build when the production code has changed since
                  the last build.
  --consumer=NAME Which consumer to run. NAME is aggregate (default) or duplicates. aggregate: the existing
                  by-extension / by-size / by-date tables. duplicates: scan for files with identical content
                  and emit a shell script that quarantines (or with --hard-delete deletes) the redundant
                  copies. Combinations modes only support --consumer=aggregate and reject anything else.
  --hard-delete   Duplicates mode only. Switches the generated script from soft delete (mv to a bin) to hard
                  delete (rm). The script prompts for the literal string DELETE before doing anything.
  --min-size=SIZE Applies to all consumers. Skip files smaller than SIZE before they enter the queue,
                  so no consumer ever sees them. SIZE is a 1024-based count: raw bytes (e.g. 4096) or one
                  of NB / NKB / NMB / NGB / NTB (case-insensitive, e.g. 1MB, 512KB). Default 0 = no filter.
                  Useful for skipping tiny config / cache / pyc files that are unlikely to matter.
  --file-extensions=LIST  Applies to all consumers. Comma-separated extension list to include; everything else
                  is skipped before it enters the queue. Tokens are case-insensitive and a leading dot is
                  optional, so --file-extensions=txt, --file-extensions=.TXT, and --file-extensions=TXT all mean the same
                  thing. The special token "none" includes files with no extension (README, Makefile,
                  .gitignore, foo.). Use --file-extensions=* (the default) to disable the filter. Examples:
                    --file-extensions=jpg,jpeg,png,gif,bmp,heic,raw,mp3,wav,m4a,flac,ogg,mp4,mov,mkv,avi,webm
                    --file-extensions=md,txt,none
  --exclude=LIST  Comma-separated directory basenames the scanner must never recurse into (in addition to
                  the always-skipped .git). Example: --exclude=node_modules,target,.mvn,build,dist,.gradle.
                  For names containing spaces, quote the WHOLE flag so the shell keeps it as one argument
                  (bash word-splits otherwise and the name "Microsoft Visual Studio" turns into three
                  tokens). The comma stays the separator inside the quotes:
                    --exclude="Microsoft Visual Studio,node_modules"
                    --exclude="Program Files,Program Files (x86),node_modules"
                  Applies to both consumers but most useful for duplicates mode, where it prevents the
                  generated script from rm-ing files that belong to build outputs / dependency caches and
                  would break the corresponding build (Maven target/, JS node_modules/, etc.).
  --out=PATH      Aggregate mode: tees stdout+stderr to a file. If PATH is an existing directory or ends with
                  '/', bash auto-names the file aggregator-YYYYMMDD-HHMMSS.out inside it. Otherwise PATH is
                  used verbatim. If --out is omitted, no file is written (stdout only).
                  Duplicates mode: tells Java where to write remove-duplicates.sh. Same directory-vs-file
                  resolution; inside a directory the file name is always remove-duplicates.sh (overwrites on
                  each run). Defaults to ./remove-duplicates.sh in the project root when omitted (start.sh
                  cd's there before launching Java). Bash does NOT tee stdout in this mode.
  --stats         Print thread count, heap usage, and queue depth once per second during the scan (helpful
                  for watching backpressure in action).
  --log-level=L   Logback root level. Default INFO. Set DEBUG to surface FolderScanner / DuplicateLocator
                  skip diagnostics (locked dirs, denied ACLs, hash failures). Forwarded as -Dlog.level=L.
  --producers=N   Number of scanner (folder-walker) threads. Default: max(8, NCPU*4); directory walking is
                  IO-bound, so over-subscribing CPUs is intentional. Retune with --combinations.
  --consumers=N   Number of consumer drainer threads. Default: max(4, NCPU*2). For the duplicate locator
                  this also sizes phase 2's ForkJoinPool that hashes the same-size candidate groups.
  --queue-type=T  BlockingQueue implementation: abq (ArrayBlockingQueue, default, pre-allocated array, single
                  lock) or lbq (LinkedBlockingQueue, one allocation per put, separate put/take locks). ABQ
                  wins on long IO-bound scans by avoiding per-put Node garbage and the GC tail-latency spikes
                  it causes.
  --queue-size=N  Bounded queue capacity. Default: 4096. Larger lets the producer run further ahead of the
                  consumer before backpressure kicks in (more RAM); smaller forces backpressure sooner.
  --combinations-q
                  Run the scan 16 times across {lbq,abq} x {4096,8192} x producers{100,64} x consumers{100,48}.
                  Verifies all 16 runs produce an identical result body and prints a sorted comparison table.
                  Overrides any --queue-type / --queue-size / --producers / --consumers on the same command.
  --combinations  Run producer/consumer counts over {4,8,16,24,32,48,64,100} (64 combinations), in randomized
                  order to avoid filesystem-cache bias. Verifies every run produces an identical result (sha256
                  of the result body) and reports the top 5 fastest and bottom 5 slowest combinations,
                  including the JVM peak thread count for each. Ignores --producers/--consumers/--stats flags.
  --out=FILE      Combinations modes: writes the sorted results table (one row per combination, with
                  seconds/threads/memMB/cpu%) plus the verification line to FILE; the per-line progress and
                  the on-screen summary still go to stdout. (Single-run / duplicates semantics described above.)
  --errors=FILE   Combinations modes only (--combinations / --combinations-q). Captures stderr from every run,
                  then writes a deduplicated, sorted list to FILE at the end. Most skip messages repeat once
                  per run, so dedup keeps the file readable.
  path            Directory to scan (default: ../..)
EOF
            exit 0 ;;
        --mermaid)
            # Undocumented dev helper: render the README's mermaid block to data-flow.png
            # via mermaid-cli. Uses npx so no global install needed; requires Node + npm.
            tmp="$(mktemp --suffix=.mmd)"
            trap 'rm -f "$tmp"' EXIT
            awk '/^```mermaid$/{f=1;next}/^```$/{f=0}f' README.md > "$tmp"
            npx -y -p @mermaid-js/mermaid-cli mmdc -i "$tmp" -o data-flow.png
            exit ;;
        -*)
            # Catch typos like "-out=..." (one dash) so they don't silently become the target path.
            echo "Unknown flag: $arg" >&2
            echo "Run ./scripts/start.sh --help for usage." >&2
            exit 2 ;;
        *)
            TARGET="$arg" ;;
    esac
done

# Defaults
if [ -z "$CONSUMER_FLAG" ]; then
    CONSUMER_FLAG="-Dconsumer=aggregate"
fi

# Combinations modes verify result-body hash equality across runs, which is
# meaningful for the aggregator's deterministic output but not for the
# duplicate finder. Fail fast instead of silently overriding the user's choice.
if { [ "$COMBINATIONS" = "1" ] || [ "$COMBINATIONS_Q" = "1" ]; } \
        && [ "$CONSUMER_FLAG" != "-Dconsumer=aggregate" ]; then
    echo "Error: --combinations / --combinations-q only support --consumer=aggregate." >&2
    echo "       Drop the consumer flag (defaults to aggregate) or pass --consumer=aggregate." >&2
    exit 2
fi

# --hard-delete is meaningless outside duplicates mode.
if [ "$HARD_DELETE" = "1" ] && [ "$CONSUMER_FLAG" != "-Dconsumer=duplicates" ]; then
    echo "Error: --hard-delete only applies with --consumer=duplicates." >&2
    exit 2
fi

# Append consumer-specific flags into FLAGS for the single-run path.
FLAGS="$FLAGS $CONSUMER_FLAG"
if [ "$HARD_DELETE" = "1" ]; then
    FLAGS="$FLAGS -Dharddelete=true"
fi
if [ -n "$MIN_SIZE_RAW" ]; then
    FLAGS="$FLAGS -Dminsize=$MIN_SIZE_RAW"
fi
if [ -n "$FILE_EXTENSIONS_RAW" ]; then
    FLAGS="$FLAGS -Dfileextensions=$FILE_EXTENSIONS_RAW"
fi
# --exclude must NOT be concatenated into the FLAGS string: bash word-splits on
# `java $FLAGS ...`, so a folder name like "Microsoft Visual Studio" would shatter
# into three tokens. Pass it as a single quoted array element instead.
EXCLUDE_FLAG=()
if [ -n "$EXCLUDE_RAW" ]; then
    EXCLUDE_FLAG=("-Dexclude=$EXCLUDE_RAW")
fi

# In duplicates mode, forward --out to Java as -Dout. In aggregate mode,
# bash still uses OUT_FILE for tee (existing behavior) but if the user
# pointed --out at a directory (or a path ending in '/'), expand it to a
# timestamped aggregator-YYYYMMDD-HHMMSS.out file inside that directory.
if [ "$CONSUMER_FLAG" = "-Dconsumer=duplicates" ] && [ -n "$OUT_FLAG_RAW" ]; then
    FLAGS="$FLAGS -Dout=$OUT_FLAG_RAW"
elif [ "$CONSUMER_FLAG" = "-Dconsumer=aggregate" ] && [ -n "$OUT_FILE" ]; then
    if [ -d "$OUT_FILE" ] || [[ "$OUT_FILE" == */ ]]; then
        OUT_FILE="${OUT_FILE%/}/aggregator-$(date +%Y%m%d-%H%M%S).out"
    fi
fi

# --build: package the executable jar via Maven and exit. We always do a clean
# package so a stale jar from a prior version can't silently shadow new code.
if [ "$BUILD" = "1" ]; then
    exec mvn -q clean package
fi

# --test[=MODE]: dispatch to JUnit (unit), the bash e2e harness (e2e), or both
# (all). Validation lives here rather than in the arg loop so a bad value
# produces a single clean error after parsing is done. e2e and all both need
# the jar; fail fast with the same message the run paths use.
if [ -n "$TEST_MODE" ]; then
    case "$TEST_MODE" in
        unit)
            exec mvn test ;;
        e2e)
            if [ ! -f "$JAR" ]; then
                echo "Jar not found: $JAR" >&2
                echo "Build it first:  ./scripts/start.sh --build" >&2
                exit 2
            fi
            exec ./scripts/e2e-test.sh ;;
        all)
            # Unit first: failures there are usually more informative than e2e
            # failures and we want them on screen before the slower harness runs.
            mvn test || exit $?
            if [ ! -f "$JAR" ]; then
                echo "Jar not found: $JAR" >&2
                echo "Build it first:  ./scripts/start.sh --build" >&2
                exit 2
            fi
            exec ./scripts/e2e-test.sh ;;
        *)
            echo "Unknown --test value: $TEST_MODE (expected unit, e2e, or all)" >&2
            exit 2 ;;
    esac
fi

# All run paths below execute Main from the jar. If the user hasn't built it
# yet, fail loudly rather than silently falling back to javac - that would
# bypass the Maven flow we just wired in.
if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    echo "Build it first:  ./scripts/start.sh --build" >&2
    exit 2
fi

if [ "$COMBINATIONS_Q" = "1" ]; then
    # Permutes queue type x queue size x producers x consumers.
    # Per-iteration -D flags appear AFTER $FLAGS so they win over any --queue-type /
    # --queue-size / --producers / --consumers the user passed on the same command.
    QT_VALUES=(lbq abq)
    QS_VALUES=(4096 8192)
    P_VALUES=(100 64)
    C_VALUES=(100 48)
    TOTAL=$(( ${#QT_VALUES[@]} * ${#QS_VALUES[@]} * ${#P_VALUES[@]} * ${#C_VALUES[@]} ))
    echo "Running $TOTAL queue/size/producers/consumers combinations on $TARGET..."
    echo ""

    # Warm up the FS cache once so the first real run isn't disproportionately slow.
    java -Dproducers=8 -Dconsumers=8 -jar "$JAR" "$TARGET" >/dev/null 2>&1 || true

    RESULTS_FILE=$(mktemp)
    ERRORS_TMP=$(mktemp)   # all runs' stderr appended here; deduped to $ERRORS_FILE at end
    REF_HASH=""
    MISMATCHES=0
    COUNT=0
    for qt in "${QT_VALUES[@]}"; do
        for qs in "${QS_VALUES[@]}"; do
            for p in "${P_VALUES[@]}"; do
                for c in "${C_VALUES[@]}"; do
                    COUNT=$((COUNT + 1))
                    OUT=$(java $FLAGS "${EXCLUDE_FLAG[@]}" -Dstats=true -Dqueuetype=$qt -Dqueuesize=$qs -Dproducers=$p -Dconsumers=$c -jar "$JAR" "$TARGET" 2>>"$ERRORS_TMP")
                    # Metrics: elapsed seconds (Done in line), peak JVM threads and heap-used MB
                    # (Run stats line), process CPU% (Run stats line).
                    SEC=$(printf "%s" "$OUT" | sed -nE 's/^Done in ([0-9.]+) s.*/\1/p')
                    PEAK=$(printf "%s\n" "$OUT" | grep -oE 'threads=[0-9]+/peak=[0-9]+' | tail -1 | cut -d= -f3)
                    MEM=$(printf "%s" "$OUT" | sed -nE 's/.*heap=([0-9]+)\/[0-9]+ MB.*/\1/p')
                    CPU=$(printf "%s" "$OUT" | sed -nE 's/.*cpu=([0-9]+)%.*/\1/p')
                    # Body hash verifies every combination produced the same aggregation result
                    # (i.e. same file count + same bytes per bucket). Skip lines that vary across
                    # runs by design (banner, timing, run-stats, periodic stats).
                    HASH=$(printf "%s\n" "$OUT" | grep -v '^Scanning ' | grep -v '^Done in ' | grep -v '^Run stats: ' | grep -v '^\[stat ' | sha256sum | cut -d' ' -f1)
                    if [ -z "$REF_HASH" ]; then
                        REF_HASH="$HASH"
                    elif [ "$HASH" != "$REF_HASH" ]; then
                        MISMATCHES=$((MISMATCHES + 1))
                        echo ""
                        echo "  !! MISMATCH at queue=$qt size=$qs producers=$p consumers=$c (hash $HASH, expected $REF_HASH)"
                    fi
                    printf "%s %s %s %s %s %s %s %s\n" "$SEC" "$qt" "$qs" "$p" "$c" "$PEAK" "$MEM" "$CPU" >> "$RESULTS_FILE"
                    printf "[%2d/%2d] queue=%-3s size=%-4s producers=%-3s consumers=%-3s  %6s s  threads=%-4s mem=%-4s MB  cpu=%s%%\n" \
                        "$COUNT" "$TOTAL" "$qt" "$qs" "$p" "$c" "$SEC" "$PEAK" "$MEM" "$CPU"
                done
            done
        done
    done
    echo ""

    if [ "$MISMATCHES" -eq 0 ]; then
        echo "Result verification: all $TOTAL runs produced an identical result body (sha256 $REF_HASH)."
    else
        echo "Result verification: $MISMATCHES of $TOTAL runs did NOT match (see warnings above)."
    fi
    echo ""
    echo "Results (sorted ascending by seconds):"
    printf "  %6s  %-5s  %-5s  %-5s  %-5s  %-8s  %-6s  %-6s\n" "s" "queue" "size" "prod" "cons" "threads" "memMB" "cpu%"
    sort -n "$RESULTS_FILE" | awk '{printf "  %6.1f  %-5s  %-5s  %-5s  %-5s  %-8d  %-6d  %-6d\n", $1, $2, $3, $4, $5, $6, $7, $8}'

    if [ -n "$OUT_FILE" ]; then
        {
            echo "# $TOTAL queue/size/producers/consumers combinations on $TARGET"
            echo "# Result verification: $([ "$MISMATCHES" -eq 0 ] && echo "all identical" || echo "$MISMATCHES mismatches") (sha256 $REF_HASH)"
            echo "# Sorted ascending by seconds."
            printf "  %6s  %-5s  %-5s  %-5s  %-5s  %-8s  %-6s  %-6s\n" "s" "queue" "size" "prod" "cons" "threads" "memMB" "cpu%"
            sort -n "$RESULTS_FILE" | awk '{printf "  %6.1f  %-5s  %-5s  %-5s  %-5s  %-8d  %-6d  %-6d\n", $1, $2, $3, $4, $5, $6, $7, $8}'
        } > "$OUT_FILE"
        echo ""
        echo "Full results written to $OUT_FILE"
    fi
    if [ -n "$ERRORS_FILE" ]; then
        # Dedup so a skip that fires once per run doesn't appear $TOTAL times.
        sort -u "$ERRORS_TMP" > "$ERRORS_FILE"
        UNIQUE_ERRORS=$(wc -l < "$ERRORS_FILE")
        echo "Unique stderr lines across all runs: $UNIQUE_ERRORS -> $ERRORS_FILE"
    fi
    rm -f "$RESULTS_FILE" "$ERRORS_TMP"
    exit 0
fi

if [ "$COMBINATIONS" = "1" ]; then
    VALUES=(4 8 16 24 32 48 64 100)
    TOTAL=$(( ${#VALUES[@]} * ${#VALUES[@]} ))
    echo "Running $TOTAL combinations on $TARGET (randomized order)..."
    echo ""

    # Build the (producers, consumers) pair list, then shuffle it.
    PAIRS_FILE=$(mktemp)
    RESULTS_FILE=$(mktemp)
    for p in "${VALUES[@]}"; do
        for c in "${VALUES[@]}"; do
            printf "%s %s\n" "$p" "$c" >> "$PAIRS_FILE"
        done
    done
    shuf "$PAIRS_FILE" > "$PAIRS_FILE.shuf"
    mv "$PAIRS_FILE.shuf" "$PAIRS_FILE"

    # Warm up the FS cache once so the first real run isn't disproportionately slow.
    java -Dproducers=8 -Dconsumers=8 $QUEUE_TYPE_FLAG $QUEUE_SIZE_FLAG -jar "$JAR" "$TARGET" >/dev/null 2>&1 || true

    REF_HASH=""
    COUNT=0
    MISMATCHES=0
    ERRORS_TMP=$(mktemp)   # all runs' stderr appended here; deduped to $ERRORS_FILE at end
    while read -r p c; do
        COUNT=$((COUNT + 1))
        # -Dstats=true so the run prints at least one "[stat ...] threads=N/peak ..." line
        # (always emitted at end-of-run by Main), which we parse for the JVM peak thread count.
        OUT=$(java -Dproducers=$p -Dconsumers=$c -Dstats=true $QUEUE_TYPE_FLAG $QUEUE_SIZE_FLAG -jar "$JAR" "$TARGET" 2>>"$ERRORS_TMP")
        # Elapsed seconds (1 decimal) from "Done in X.X s." or "Done in X.X s (Y.Y m).".
        SEC=$(printf "%s" "$OUT" | sed -nE 's/^Done in ([0-9.]+) s.*/\1/p')
        # Peak JVM thread count: take the last "threads=N/peak" occurrence from either a
        # "[stat ...]" line or the final "Run stats:" line; peak is monotonic so this is
        # the true high-water mark for the run.
        PEAK=$(printf "%s\n" "$OUT" | grep -oE 'threads=[0-9]+(/peak=|/)[0-9]+' | tail -1 | sed -E 's/.*[/=]([0-9]+)$/\1/')
        # Body hash: skip the banner (varies with p/c), the timing/run-stats lines, and
        # every periodic stat line (all vary across runs).
        HASH=$(printf "%s\n" "$OUT" | grep -v '^Scanning ' | grep -v '^Done in ' | grep -v '^Run stats: ' | grep -v '^\[stat ' | sha256sum | cut -d' ' -f1)
        if [ -z "$REF_HASH" ]; then
            REF_HASH="$HASH"
        elif [ "$HASH" != "$REF_HASH" ]; then
            MISMATCHES=$((MISMATCHES + 1))
            echo ""
            echo "  !! MISMATCH at producers=$p consumers=$c (hash $HASH, expected $REF_HASH)"
        fi
        printf "%s %s %s %s\n" "$SEC" "$p" "$c" "$PEAK" >> "$RESULTS_FILE"
        printf "\r[%2d/%2d] producers=%-3d consumers=%-3d  %6s s  peakThreads=%-4s" "$COUNT" "$TOTAL" "$p" "$c" "$SEC" "$PEAK"
    done < "$PAIRS_FILE"
    echo ""
    echo ""

    if [ "$MISMATCHES" -eq 0 ]; then
        echo "Result verification: all $TOTAL runs produced an identical result body (sha256 $REF_HASH)."
    else
        echo "Result verification: $MISMATCHES of $TOTAL runs did NOT match (see warnings above)."
    fi
    echo ""
    echo "Top 5 fastest combinations:"
    printf "  %6s  %-12s  %-12s  %-12s\n" "s" "producers" "consumers" "peakThreads"
    sort -n "$RESULTS_FILE" | head -5 | awk '{printf "  %6.1f  %-12d  %-12d  %-12d\n", $1, $2, $3, $4}'
    echo ""
    echo "Bottom 5 slowest combinations:"
    printf "  %6s  %-12s  %-12s  %-12s\n" "s" "producers" "consumers" "peakThreads"
    sort -nr "$RESULTS_FILE" | head -5 | awk '{printf "  %6.1f  %-12d  %-12d  %-12d\n", $1, $2, $3, $4}'

    if [ -n "$OUT_FILE" ]; then
        {
            echo "# $TOTAL (producers x consumers) combinations on $TARGET"
            echo "# Result verification: $([ "$MISMATCHES" -eq 0 ] && echo "all identical" || echo "$MISMATCHES mismatches") (sha256 $REF_HASH)"
            echo "# Columns: seconds producers consumers peakThreads (sorted ascending by seconds)"
            sort -n "$RESULTS_FILE"
        } > "$OUT_FILE"
        echo ""
        echo "Full results written to $OUT_FILE"
    fi
    if [ -n "$ERRORS_FILE" ]; then
        # Dedup so a skip that fires once per run doesn't appear $TOTAL times.
        sort -u "$ERRORS_TMP" > "$ERRORS_FILE"
        UNIQUE_ERRORS=$(wc -l < "$ERRORS_FILE")
        echo "Unique stderr lines across all runs: $UNIQUE_ERRORS -> $ERRORS_FILE"
    fi

    rm -f "$PAIRS_FILE" "$RESULTS_FILE" "$ERRORS_TMP"
    exit 0
fi

# In duplicates mode the script is written by Java via -Dout; do NOT tee
# the run's stdout to OUT_FILE (that would clobber the generated .sh).
if [ -n "$OUT_FILE" ] && [ "$CONSUMER_FLAG" != "-Dconsumer=duplicates" ]; then
    # Tee stdout+stderr so the file captures the same thing the terminal sees.
    exec java $FLAGS "${EXCLUDE_FLAG[@]}" -jar "$JAR" "$TARGET" 2>&1 | tee "$OUT_FILE"
else
    exec java $FLAGS "${EXCLUDE_FLAG[@]}" -jar "$JAR" "$TARGET"
fi
