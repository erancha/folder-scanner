#!/usr/bin/env bash
# End-to-end suite for the folder-scanner CLI: builds a tiny fixture tree under a tmpdir and runs
# the jar through scripts/start.sh in aggregate, duplicates, and filemanager modes, asserting on
# stdout, the generated scripts, and exit codes. Runnable standalone or via ./scripts/tests.sh
# --e2e/--all; requires a prebuilt jar and does NOT rebuild it (./scripts/start.sh --build).
set -uo pipefail
# Run from the repo root so the JAR glob and ./scripts/start.sh invocations below resolve.
cd "$(dirname "$0")/.."
HERE="$(pwd)"
# Glob the shaded jar rather than pin a version: the POM is the single version source.
JAR=$(echo "$HERE"/target/folder-scanner-*.jar)

if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    echo "Build it first:  ./scripts/start.sh --build" >&2
    exit 2
fi

PASS=0
FAIL=0
FAILURES=()

ok() {
    PASS=$((PASS + 1))
    printf "  OK    %s\n" "$1"
}

# Records $1 in FAILURES (replayed by the end summary) besides printing it with diagnostic $2.
fail() {
    FAIL=$((FAIL + 1))
    FAILURES+=("$1")
    printf "  FAIL  %s\n        %s\n" "$1" "$2"
}

# $1 = test name, $2 = expected, $3 = actual.
assert_eq() {
    if [ "$2" = "$3" ]; then ok "$1"
    else fail "$1" "expected [$2] got [$3]"
    fi
}

# $1 = test name, $2 = haystack, $3 = needle.
assert_contains() {
    case "$2" in
        *"$3"*) ok "$1" ;;
        *) fail "$1" "missing substring [$3] in output" ;;
    esac
}

# $1 = test name, $2 = haystack, $3 = needle (asserted absent).
assert_not_contains() {
    case "$2" in
        *"$3"*) fail "$1" "unexpected substring [$3] in output" ;;
        *) ok "$1" ;;
    esac
}

# $1 = test name, $2 = expected code, $3 = actual code.
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
for launcher in scripts/tests-e2e.sh scripts/start.sh; do
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
# Filter is producer-side, so it applies to aggregate too. With 1KB threshold:
# tiny.txt (13 B) + dup_a.bin (17 B) + dup_b.bin (17 B) are dropped; unique.bin
# (2048 B) and big.bin (~1.05 MB) survive. Files=2 plus a Filtered (size...) line.
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=aggregate --min-size=1KB "$FIXTURE" 2>&1)" || true
assert_contains "min_size_files_count" "$OUT" "Files=2"
assert_contains "min_size_report_line" "$OUT" "Skipped (size < 1.00 KB): 3 files"
# The min-size threshold is echoed as an opener line right after Excluding; the
# run-level summary (Done / Run stats) trails the detail tables.
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
if [ -n "$DONE_LN" ] && [ -n "$TABLE_LN" ] && [ "$TABLE_LN" -lt "$DONE_LN" ]; then
    ok "detail_tables_precede_done_line"
else
    fail "detail_tables_precede_done_line" "table at [$TABLE_LN], Done at line [$DONE_LN]"
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
# --hard-delete applies to filemanager delete (exit 0), emitting rm lines and no trash bin.
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

# ---- test 15: a runtime write failure exits cleanly, not as a raw stack trace ----
# Config/validation errors exit 2 with a leveled SLF4J line; a runtime IO failure writing the
# generated script must join the same contract instead of escaping uncaught (exit 1, raw stack
# trace). Force the failure: a regular file occupies the slot where the script's parent directory
# must be, so writing the generated script fails.
BLOCKER="$SCRATCH/blocker-file"
printf 'x' > "$BLOCKER"
WRITE_ERR="$(./scripts/start.sh "$EXCLUDE" --consumer=duplicates "--out=$BLOCKER/remove-duplicates.sh" "$FIXTURE" 2>&1 >/dev/null)"
assert_exit_code "runtime_write_failure_exits_2" "2" "$?"
assert_contains "runtime_write_failure_leveled_message" "$WRITE_ERR" "failed to write"
assert_not_contains "runtime_write_failure_no_stack_trace" "$WRITE_ERR" "Exception in thread"

# ---- test 16: folders consumer ranks subtrees and echoes the recursive threshold -
# Recursive subtree bytes under default EXCLUDE: unique/ (~1.05 MB) is the only folder
# besides the scan root that clears a 1MB --min-size-recursive; small/ (30 B) and medium/
# (~2 KB) are dropped. The root is always shown and, holding everything, ranks first. The
# threshold is echoed in the scan header on its own line, only for this consumer.
OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=folders --min-size-recursive=1MB "$FIXTURE" 2>&1)" || true
assert_contains "folders_header_echoes_recursive_threshold" "$OUT" "Min size recursive: 1.00 MB"
UNIQUE_ROW="$(printf '%s\n' "$OUT" | grep -E "[[:space:]]$FIXTURE/unique$" || true)"
assert_contains "folders_lists_large_subtree" "$UNIQUE_ROW" "$FIXTURE/unique"
MEDIUM_ROW="$(printf '%s\n' "$OUT" | grep -E "[[:space:]]$FIXTURE/medium$" || true)"
assert_eq "folders_drops_subtree_below_threshold" "" "$MEDIUM_ROW"
# The scan root holds every file, so it ranks above the unique/ subtree it contains.
ROOT_LN="$(printf '%s\n' "$OUT" | grep -nE "[[:space:]]$FIXTURE$" | head -1 | cut -d: -f1)"
UNIQUE_LN="$(printf '%s\n' "$OUT" | grep -nE "[[:space:]]$FIXTURE/unique$" | head -1 | cut -d: -f1)"
if [ -n "$ROOT_LN" ] && [ -n "$UNIQUE_LN" ] && [ "$ROOT_LN" -lt "$UNIQUE_LN" ]; then
    ok "folders_root_outranks_its_descendant"
else
    fail "folders_root_outranks_its_descendant" "root at [$ROOT_LN], unique at [$UNIQUE_LN]"
fi
# The recursive-threshold line is folders-only: the aggregate header must not carry it.
AGG_OUT="$(./scripts/start.sh "$EXCLUDE" --consumer=aggregate "$FIXTURE" 2>&1)" || true
assert_not_contains "non_folders_header_omits_recursive_threshold" "$AGG_OUT" "Min size recursive:"

# ---- test 17: folders --baseline reports day-over-day growth and new folders ----
# A dedicated tree with two ~100KB subtrees. Run 1 seeds the baseline (nothing to compare).
# Before run 2: grower/ gains 50%, stable/ is untouched, and a brand-new fresh/ subtree appears.
# Run 2 must flag grower/ in the growth section, leave stable/ out of it, and list fresh/ in its
# own "New folders" section (a new subtree has no growth percentage, so it would otherwise vanish).
GROWTH_ROOT="$SCRATCH/growth"
mkdir -p "$GROWTH_ROOT/grower" "$GROWTH_ROOT/stable"
head -c 100000 /dev/urandom > "$GROWTH_ROOT/grower/g.bin"
head -c 100000 /dev/urandom > "$GROWTH_ROOT/stable/s.bin"
BASELINE="$SCRATCH/baseline.tsv"
# --min-size-recursive=0 so the ~100KB fixture folders are tracked despite the 10MB default.
OUT="$(./scripts/start.sh --consumer=folders --min-size-recursive=0 "--baseline=$BASELINE" "$GROWTH_ROOT" 2>&1)" || true
assert_contains "folders_baseline_first_run_seeds" "$OUT" "no prior baseline to compare"
if [ -f "$BASELINE" ]; then ok "folders_baseline_file_written"
else fail "folders_baseline_file_written" "expected $BASELINE to be written"; fi

head -c 50000 /dev/urandom >> "$GROWTH_ROOT/grower/g.bin"   # grower/ +50%, stable/ unchanged
mkdir -p "$GROWTH_ROOT/fresh"                               # fresh/ is absent from the baseline
head -c 100000 /dev/urandom > "$GROWTH_ROOT/fresh/n.bin"
OUT="$(./scripts/start.sh --consumer=folders --min-size-recursive=0 "--baseline=$BASELINE" "$GROWTH_ROOT" 2>&1)" || true
# Scope each section: growth is between its header and the "New folders" header; new is from there on.
GROWTH_SECTION="$(printf '%s\n' "$OUT" | sed -n '/Folder growth since/,/New folders since/p')"
NEW_SECTION="$(printf '%s\n' "$OUT" | sed -n '/New folders since/,$p')"
assert_contains "folders_growth_section_present" "$OUT" "Folder growth since"
assert_contains "folders_growth_lists_grown_folder" "$GROWTH_SECTION" "$GROWTH_ROOT/grower"
assert_not_contains "folders_growth_omits_stable_folder" "$GROWTH_SECTION" "$GROWTH_ROOT/stable"
assert_not_contains "folders_growth_omits_new_folder" "$GROWTH_SECTION" "$GROWTH_ROOT/fresh"
assert_contains "folders_new_section_present" "$OUT" "New folders since"
assert_contains "folders_new_section_lists_fresh_folder" "$NEW_SECTION" "$GROWTH_ROOT/fresh"

# ---- test 18: --examples is a --help modifier; the example block requires --help ----
# --help --examples shows usage plus the per-consumer block.
OUT="$(./scripts/start.sh --help --examples 2>&1)"
assert_exit_code "help_examples_exits_0" "0" "$?"
assert_contains "help_examples_block_header" "$OUT" "Examples ("
assert_contains "help_examples_covers_folders" "$OUT" "--consumer=folders"
assert_contains "help_examples_covers_baseline_growth" "$OUT" "--baseline"
# Plain --help stays concise: the block is opt-in.
HELP_OUT="$(./scripts/start.sh --help 2>&1)"
assert_not_contains "help_alone_omits_examples" "$HELP_OUT" "Examples ("
# --examples without --help is misuse: no scan runs, exit 2, no block printed.
ALONE="$(./scripts/start.sh --examples 2>&1)"
assert_exit_code "examples_without_help_exits_2" "2" "$?"
assert_not_contains "examples_without_help_no_block" "$ALONE" "Examples ("

echo
echo "----------------------------------------"
echo "Passed: $PASS    Failed: $FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf "Failures:\n"
    for name in "${FAILURES[@]}"; do printf "  - %s\n" "$name"; done
    exit 1
fi
exit 0
