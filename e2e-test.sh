#!/usr/bin/env bash
# End-to-end tests for the folder-scanner CLI. Builds a tiny fixture tree under a
# tmpdir, exercises start.sh in aggregate and duplicates modes, and asserts on
# both stdout and the generated remove-duplicates.sh. Three additional tests
# pin the start.sh error-path branches (unknown flag, missing target dir,
# --hard-delete without --consumer=duplicates) that the JUnit suite cannot
# reach because they live in the bash dispatcher, not in Java.
#
# Run directly (./e2e-test.sh) or via ./start.sh --test=e2e (which fails fast
# if the jar is missing). Sequential; no parallelism by design - seven tests
# at ~3 s each finish in well under a minute.
set -uo pipefail
cd "$(dirname "$0")"
HERE="$(pwd)"
JAR="$HERE/target/folder-scanner-1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    echo "Build it first:  ./start.sh --build" >&2
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
# duplicate pair. Bucket distribution: 3 files in LE_1KB (tiny.txt + the dup
# pair at 16 bytes each), 1 in LE_1MB (2048 bytes), 1 in LE_1GB (~1.05 MB).
build_fixture() {
    mkdir -p "$FIXTURE/small" "$FIXTURE/medium" "$FIXTURE/unique"
    printf 'tiny content\n' > "$FIXTURE/small/tiny.txt"        # 13 bytes -> LE_1KB
    # Duplicate pair: identical 16-byte content in two different paths so the
    # locator must group them across directories.
    printf 'duplicate-payload' > "$FIXTURE/small/dup_a.bin"    # 17 bytes -> LE_1KB
    printf 'duplicate-payload' > "$FIXTURE/medium/dup_b.bin"   # 17 bytes -> LE_1KB
    # 2048 distinct bytes -> LE_1MB (> 1KB, <= 1MB).
    head -c 2048 /dev/urandom > "$FIXTURE/medium/unique.bin"
    # ~1.05 MB distinct bytes -> LE_1GB (> 1MB, <= 1GB).
    head -c 1100000 /dev/urandom > "$FIXTURE/unique/big.bin"
}

FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT
build_fixture

EXCLUDE="--exclude=.git"   # required by Main; no .git in the fixture but the flag is mandatory

echo "Fixture: $FIXTURE  (5 files: 3 in LE_1KB incl. one dup pair, 1 in LE_1MB, 1 in LE_1GB)"
echo

# ---- test 1: aggregate prints the right total file count -------------------
OUT="$(./start.sh "$EXCLUDE" --consumer=aggregate "$FIXTURE" 2>&1)" || true
assert_contains "aggregate_headline_files_count" "$OUT" "Files=5"

# ---- test 2: aggregate by-size table distributes correctly -----------------
# The Aggregator labels are "<= 1KB", "> 1KB and <= 1MB", "> 1MB and <= 1GB".
# Grep just the data row for each so the test does not depend on column widths.
LE1KB_ROW="$(printf "%s" "$OUT" | grep -E '^\s*<= 1KB\b' || true)"
assert_contains "aggregate_bucket_le1kb_count_3" "$LE1KB_ROW" " 3 "
LE1MB_ROW="$(printf "%s" "$OUT" | grep -E '^\s*> 1KB and <= 1MB\b' || true)"
assert_contains "aggregate_bucket_le1mb_count_1" "$LE1MB_ROW" " 1 "
LE1GB_ROW="$(printf "%s" "$OUT" | grep -E '^\s*> 1MB and <= 1GB\b' || true)"
assert_contains "aggregate_bucket_le1gb_count_1" "$LE1GB_ROW" " 1 "

# ---- test 3: duplicates mode generates a script with the right group count -
SCRIPT="$FIXTURE/remove-duplicates.sh"
OUT="$(./start.sh "$EXCLUDE" --consumer=duplicates "--out=$SCRIPT" "$FIXTURE" 2>&1)" || true
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
./start.sh --not-a-real-flag >/dev/null 2>&1
assert_exit_code "unknown_flag_exits_2" "2" "$?"

# ---- test 6: missing target dir fails (Java side, exit 2) ------------------
./start.sh "$EXCLUDE" "/nonexistent-xyz-folder-scanner-e2e-$$" >/dev/null 2>&1
assert_exit_code "missing_target_dir_exits_2" "2" "$?"

# ---- test 7: --hard-delete without --consumer=duplicates is rejected -------
./start.sh "$EXCLUDE" --hard-delete "$FIXTURE" >/dev/null 2>&1
assert_exit_code "hard_delete_without_duplicates_exits_2" "2" "$?"

echo
echo "----------------------------------------"
echo "Passed: $PASS    Failed: $FAIL"
if [ "$FAIL" -gt 0 ]; then
    printf "Failures:\n"
    for name in "${FAILURES[@]}"; do printf "  - %s\n" "$name"; done
    exit 1
fi
exit 0
