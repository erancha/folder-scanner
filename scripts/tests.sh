#!/usr/bin/env bash
# Test runner for the folder-scanner CLI. Dispatches the JUnit unit suite, the bash end-to-end
# suite, or both. The e2e suite builds a tiny fixture tree under a tmpdir, exercises
# scripts/start.sh in aggregate, duplicates, and filemanager modes, and asserts on both stdout and
# the generated scripts (remove-duplicates.sh, delete-files.sh). Exit-code tests pin the contract
# end-to-end through start.sh -> jar (unknown flag, missing target dir, --hard-delete misuse); the
# underlying validation now lives in Java (picocli + Cli) and is also covered by the JUnit CliTest.
set -uo pipefail
# This script lives in scripts/; cd to the project root so the JAR path and the
# ./scripts/start.sh invocations below resolve against the repo layout no matter
# where the caller launched us from.
cd "$(dirname "$0")/.."
HERE="$(pwd)"
# Glob the shaded jar rather than pin a version: the POM is the single version source.
JAR=$(echo "$HERE"/target/folder-scanner-*.jar)

usage() {
    cat <<EOF
Usage: ./scripts/tests.sh [--unit | --e2e | --all]

  --unit   Run the JUnit unit suite only (no jar needed).
  --e2e    Run the bash end-to-end suite only. Requires the jar; build it with
           ./scripts/start.sh --build (this runner does NOT rebuild it).
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

# E2E phase (jar required). Does NOT rebuild; pair with ./scripts/start.sh --build.
if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    echo "Build it first:  ./scripts/start.sh --build" >&2
    exit 2
fi

PASS=0
FAIL=0
FAILURES=()

# Print a green-ish OK line and bump the pass counter.
ok() {
    PASS=$((PASS + 1))
    printf "  OK    %s\n" "$1"
}

# Print a loud FAIL line with the diagnostic, record the failure name for the
# end summary, and bump the fail counter.
fail() {
    FAIL=$((FAIL + 1))
    FAILURES+=("$1")
    printf "  FAIL  %s\n        %s\n" "$1" "$2"
}

# Assert two strings are equal. $1 = test name, $2 = expected, $3 = actual.
assert_eq() {
    if [ "$2" = "$3" ]; then ok "$1"
    else fail "$1" "expected [$2] got [$3]"
    fi
}

# Assert haystack contains needle. $1 = test name, $2 = haystack, $3 = needle.
assert_contains() {
    case "$2" in
        *"$3"*) ok "$1" ;;
        *) fail "$1" "missing substring [$3] in output" ;;
    esac
}

# Assert haystack does NOT contain needle.
assert_not_contains() {
    case "$2" in
        *"$3"*) fail "$1" "unexpected substring [$3] in output" ;;
        *) ok "$1" ;;
    esac
}

# Assert an exit code. $1 = test name, $2 = expected code, $3 = actual code.
assert_exit_code() {
    if [ "$2" = "$3" ]; then ok "$1"
    else fail "$1" "expected exit $2 got $3"
    fi
}

# Build a fixture tree under FIXTURE with a known file count and one known
# duplicate pair. Bucket distribution (with default EXCLUDE, which skips
# node_modules): 3 files in LE_1KB (tiny.txt + the dup pair at 17 bytes each),
# 1 in LE_1MB (2048 bytes), 1 in LE_1GB (~1.05 MB). The fixture also contains
# a 6th file under node_modules/ that the --exclude test exposes.
build_fixture() {
    mkdir -p "$FIXTURE/small" "$FIXTURE/medium" "$FIXTURE/unique" "$FIXTURE/node_modules"
    printf 'tiny content\n' > "$FIXTURE/small/tiny.txt"        # 13 bytes -> LE_1KB
    # Duplicate pair: identical 17-byte content in two different paths so the
    # locator must group them across directories.
    printf 'duplicate-payload' > "$FIXTURE/small/dup_a.bin"    # 17 bytes -> LE_1KB
    printf 'duplicate-payload' > "$FIXTURE/medium/dup_b.bin"   # 17 bytes -> LE_1KB
    # 2048 distinct bytes -> LE_1MB (> 1KB, <= 1MB).
    head -c 2048 /dev/urandom > "$FIXTURE/medium/unique.bin"
    # ~1.05 MB distinct bytes -> LE_1GB (> 1MB, <= 1GB).
    head -c 1100000 /dev/urandom > "$FIXTURE/unique/big.bin"
    # Lives under node_modules/ so the default EXCLUDE drops it. The --exclude
    # test runs without node_modules in the exclude list to confirm the file
    # IS there and the flag actually changes behavior.
    printf 'cache' > "$FIXTURE/node_modules/junk.bin"          # 5 bytes -> LE_1KB
}

FIXTURE="$(mktemp -d)"
# Generated artifacts (the duplicates script) live OUTSIDE $FIXTURE so they don't
# inflate the file count for subsequent aggregator tests, which scan $FIXTURE root.
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$FIXTURE" "$SCRATCH"' EXIT
build_fixture

# Default exclude list: .git (always-skipped policy) + node_modules (so the
# extra file we added for the --exclude test doesn't perturb the other tests'
# counts). Individual tests can pass a different --exclude to opt out.
EXCLUDE="--exclude=.git,node_modules"

echo "Fixture: $FIXTURE  (6 files total; 5 visible under default EXCLUDE: 3 in LE_1KB incl. one dup pair, 1 in LE_1MB, 1 in LE_20MB; 1 under node_modules/)"
echo

# ---- test 0: launchers derive the jar name, not pin the POM version ---------
# Guards the "POM is the single version source" invariant end-to-end: a version
# bump must not strand the harness on a stale jar name. Reads the version the
# build resolved into version.properties (the same POM source the CLI reports)
# and asserts neither launcher hardcodes that version in its jar path. The rest
# of the suite — which runs the jar through start.sh — proves the derived path
# actually resolves to the built artifact.
POMVER="$(sed -n 's/^version=//p' "$HERE/target/classes/version.properties")"
for launcher in scripts/tests.sh scripts/start.sh; do
    if grep -q "folder-scanner-$POMVER.jar" "$launcher"; then
        fail "jar_path_not_version_pinned:$launcher" "literal folder-scanner-$POMVER.jar pins the version"
    else
        ok "jar_path_not_version_pinned:$launcher"
    fi
done

# ---- test 1: aggregate prints the right total file count -------------------
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=aggregate "$FIXTURE" 2>&1)" || true
assert_contains "aggregate_headline_files_count" "$OUT" "Files=5"

# ---- test 2: aggregate by-size table distributes correctly -----------------
# The Aggregator labels are "<= 1KB", "> 1KB and <= 1MB", "> 1MB and <= 1GB".
# Grep just the data row for each so the test does not depend on column widths.
LE1KB_ROW="$(printf "%s" "$OUT" | grep -E '^\s*<= 1KB\b' || true)"
assert_contains "aggregate_bucket_le1kb_count_3" "$LE1KB_ROW" " 3 "
LE1MB_ROW="$(printf "%s" "$OUT" | grep -E '^\s*> 1KB and <= 1MB\b' || true)"
assert_contains "aggregate_bucket_le1mb_count_1" "$LE1MB_ROW" " 1 "
LE20MB_ROW="$(printf "%s" "$OUT" | grep -E '^\s*> 1MB and <= 20MB\b' || true)"
assert_contains "aggregate_bucket_le20mb_count_1" "$LE20MB_ROW" " 1 "

# ---- test 3: duplicates mode generates a script with the right group count -
SCRIPT="$SCRATCH/remove-duplicates.sh"
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=duplicates "--out=$SCRIPT" "$FIXTURE" 2>&1)" || true
assert_contains "duplicates_headline_one_group" "$OUT" "Duplicates: 1 groups, 1 redundant files"
if [ -f "$SCRIPT" ]; then
    ok "duplicates_script_file_exists"
    GROUP_HEADERS="$(grep -c '^# ---- size=' "$SCRIPT" || true)"
    assert_eq "duplicates_script_group_header_count" "1" "$GROUP_HEADERS"
else
    fail "duplicates_script_file_exists" "expected $SCRIPT to be written"
fi

# ---- test 4: keeper is commented, redundant is an active mv ----------------
if [ -f "$SCRIPT" ]; then
    KEPT_LINES="$(grep -c '# KEPT' "$SCRIPT" || true)"
    assert_eq "duplicates_one_kept_line_per_group" "1" "$KEPT_LINES"
    # Exactly one active `mv` line (the redundant) for our 2-file group.
    ACTIVE_MV="$(grep -cE '^mv ' "$SCRIPT" || true)"
    assert_eq "duplicates_one_active_mv_line" "1" "$ACTIVE_MV"
fi

# ---- test 5: unknown flag exits 2 ------------------------------------------
./scripts/start.sh --not-a-real-flag >/dev/null 2>&1
assert_exit_code "unknown_flag_exits_2" "2" "$?"

# ---- test 6: missing target dir fails (Java side, exit 2) ------------------
./scripts/start.sh "$EXCLUDE" "/nonexistent-xyz-folder-scanner-e2e-$$" >/dev/null 2>&1
assert_exit_code "missing_target_dir_exits_2" "2" "$?"

# ---- test 7: --hard-delete without --consumer=duplicates is rejected -------
./scripts/start.sh "$EXCLUDE" --hard-delete "$FIXTURE" >/dev/null 2>&1
assert_exit_code "hard_delete_without_duplicates_exits_2" "2" "$?"

# ---- test 8: --exclude actually excludes the listed directory --------------
# Default EXCLUDE skips node_modules. Run with ONLY .git excluded to prove the
# scanner does descend into node_modules when not told to skip it (file count
# bumps from 5 to 6), and then re-run with node_modules excluded to confirm
# the flag is what's hiding the file.
OUT="$(./scripts/start.sh --exclude=.git --consumer=aggregate "$FIXTURE" 2>&1)" || true
assert_contains "exclude_off_sees_node_modules_file" "$OUT" "Files=6"
OUT="$(./scripts/start.sh --exclude=.git,node_modules --consumer=aggregate "$FIXTURE" 2>&1)" || true
assert_contains "exclude_on_hides_node_modules_file" "$OUT" "Files=5"

# ---- test 9: --min-size filters small files at the producer ----------------
# Filter is now producer-side, so it applies to aggregate too. With 1KB threshold:
# tiny.txt (13 B) + dup_a.bin (17 B) + dup_b.bin (17 B) are dropped; unique.bin
# (2048 B) and big.bin (~1.05 MB) survive. Files=2 plus a Filtered (size...) line.
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=aggregate --min-size=1KB "$FIXTURE" 2>&1)" || true
assert_contains "min_size_files_count" "$OUT" "Files=2"
assert_contains "min_size_report_line" "$OUT" "Skipped (size < 1.00 KB): 3 files"
# The min-size threshold is echoed as an opener line right after Excluding, and
# the run-level summary (Done / Run stats) now sits in the openers above the
# detail tables rather than trailing them.
assert_contains "min_size_opener_line" "$OUT" "Min size: 1.00 KB"
# With no extension filter the openers echo the all-extensions sentinel, sitting
# between Excluding and Min size.
assert_contains "all_extensions_opener_line" "$OUT" "Extensions: [*]"
EXCL_LN="$(printf '%s\n' "$OUT" | grep -n '^Excluding directories:' | head -1 | cut -d: -f1)"
EXT_LN="$(printf '%s\n' "$OUT" | grep -n '^Extensions:' | head -1 | cut -d: -f1)"
MIN_LN="$(printf '%s\n' "$OUT" | grep -n '^Min size:' | head -1 | cut -d: -f1)"
if [ -n "$EXCL_LN" ] && [ -n "$EXT_LN" ] && [ -n "$MIN_LN" ] \
        && [ "$EXCL_LN" -lt "$EXT_LN" ] && [ "$EXT_LN" -lt "$MIN_LN" ]; then
    ok "extensions_opener_between_excluding_and_minsize"
else
    fail "extensions_opener_between_excluding_and_minsize" \
        "Excluding[$EXCL_LN] Extensions[$EXT_LN] Min size[$MIN_LN]"
fi
DONE_LN="$(printf '%s\n' "$OUT" | grep -n '^Done in ' | head -1 | cut -d: -f1)"
TABLE_LN="$(printf '%s\n' "$OUT" | grep -n 'By size bucket:' | head -1 | cut -d: -f1)"
if [ -n "$DONE_LN" ] && [ -n "$TABLE_LN" ] && [ "$DONE_LN" -lt "$TABLE_LN" ]; then
    ok "done_line_precedes_detail_tables"
else
    fail "done_line_precedes_detail_tables" "Done at line [$DONE_LN], table at [$TABLE_LN]"
fi

# ---- test 10: --file-extensions filters non-matching extensions at the producer -
# Only .txt allowed. Drops the four .bin files (dup_a, dup_b, unique, big);
# tiny.txt (13 B) survives. Files=1 plus a Filtered (type...) line counting 4.
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=aggregate --file-extensions=txt "$FIXTURE" 2>&1)" || true
assert_contains "file_extensions_files_count" "$OUT" "Files=1"
assert_contains "file_extensions_report_line" "$OUT" "Skipped (extension not in [txt]): 4 files"
assert_contains "file_extensions_opener_line" "$OUT" "Extensions: [txt]"
# The by-extension table should contain a txt row but no bin row.
TXT_ROW="$(printf "%s" "$OUT" | grep -E '^\s*txt\b' || true)"
assert_contains "file_extensions_txt_row_present" "$TXT_ROW" "txt"
BIN_ROW="$(printf "%s" "$OUT" | grep -E '^\s*bin\b' || true)"
assert_eq "file_extensions_no_bin_row" "" "$BIN_ROW"

# ---- test 11: --min-size + --file-extensions together at the producer -----------
# Size is checked first, so tiny.txt (13 B, .txt) is credited to the size
# bucket only — it is not also counted as an extension-filtered file. Survivors:
# unique.bin (2048 B) + big.bin (~1.05 MB), both passing both filters.
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=aggregate --min-size=1KB --file-extensions=bin "$FIXTURE" 2>&1)" || true
assert_contains "producer_filter_files_count" "$OUT" "Files=2"
assert_contains "producer_filter_size_report" "$OUT" "Skipped (size < 1.00 KB):"
assert_contains "producer_filter_type_report" "$OUT" "Skipped (extension not in [bin]):"
BIN_ROW="$(printf "%s" "$OUT" | grep -E '^\s*bin\b' || true)"
assert_contains "producer_filter_bin_row_present" "$BIN_ROW" "bin"
TXT_ROW="$(printf "%s" "$OUT" | grep -E '^\s*txt\b' || true)"
assert_eq "producer_filter_no_txt_row" "" "$TXT_ROW"

# ---- test 12: filemanager list reports every surviving file -----------------
# Default EXCLUDE leaves 5 files visible. The listing prints one path/size/modified
# line per file plus a "Listed N files" summary.
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=filemanager "$FIXTURE" 2>&1)" || true
assert_contains "filemanager_list_total" "$OUT" "Listed 5 files"
assert_contains "filemanager_list_shows_a_known_file" "$OUT" "tiny.txt"

# ---- test 13: filemanager delete (soft) writes a quarantine script ----------
# Every surviving file is a deletion target, so the script holds one active mv per
# file (5) moving into the trash bin.
DSCRIPT="$SCRATCH/delete-files.sh"
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=filemanager --action=delete "--out=$DSCRIPT" "$FIXTURE" 2>&1)" || true
assert_contains "filemanager_delete_headline" "$OUT" "to quarantine"
if [ -f "$DSCRIPT" ]; then
    ok "filemanager_delete_script_file_exists"
    MV_LINES="$(grep -cE '^mv ' "$DSCRIPT" || true)"
    assert_eq "filemanager_delete_one_mv_per_file" "5" "$MV_LINES"
    BIN_LINES="$(grep -c '^BIN=' "$DSCRIPT" || true)"
    assert_eq "filemanager_delete_soft_has_trash_bin" "1" "$BIN_LINES"
else
    fail "filemanager_delete_script_file_exists" "expected $DSCRIPT to be written"
fi

# ---- test 14: filemanager --action=delete --hard-delete is allowed ----------
# Widened rule: --hard-delete now applies to filemanager delete (exit 0), emitting rm
# lines and no trash bin.
HSCRIPT="$SCRATCH/delete-hard.sh"
./scripts/start.sh "$EXCLUDE" --consumer=filemanager --action=delete --hard-delete "--out=$HSCRIPT" "$FIXTURE" >/dev/null 2>&1
assert_exit_code "filemanager_hard_delete_allowed_exit_0" "0" "$?"
if [ -f "$HSCRIPT" ]; then
    RM_LINES="$(grep -cE '^rm ' "$HSCRIPT" || true)"
    assert_eq "filemanager_hard_delete_one_rm_per_file" "5" "$RM_LINES"
    assert_not_contains "filemanager_hard_delete_no_trash_bin" "$(cat "$HSCRIPT")" "BIN="
else
    fail "filemanager_hard_delete_script_file_exists" "expected $HSCRIPT to be written"
fi

echo
echo "----------------------------------------"
echo "Passed: $PASS    Failed: $FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf "Failures:\n"
    for name in "${FAILURES[@]}"; do printf "  - %s\n" "$name"; done
    exit 1
fi
exit 0
