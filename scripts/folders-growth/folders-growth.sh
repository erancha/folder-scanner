#!/usr/bin/env bash
set -euo pipefail
# Resolve paths against this script's location (scripts/folders-growth/) without changing the working
# directory, so baseline snapshots and reports land wherever the script is invoked from — like the .cmd.
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

# Glob the shaded jar rather than pin a version: the POM is the single version source.
JAR=$(echo "$SCRIPT_DIR"/../../target/folder-scanner-*.jar)

# Dated baseline snapshots (one .tsv per day) and the cron log live side by side here.
BASELINE_DIR="$SCRIPT_DIR/folder-sizes"
SELF="$SCRIPT_DIR/$(basename "$0")"

EXCLUDE="Windows,ProgramData,Program Files,Program Files (x86),\$Recycle.Bin,System Volume Information,workspaceStorage,extensions,.idea,.git,node_modules,target,.mvn,build,dist,.gradle,bin,EBWebView,WebviewCacheX64,ebview2_user_data,cef_cache,WidevineCdm,component_crx_cache,AmazonQ,puppeteer,.nuget,Adobe,AzureFunctionsTools,CSharpier,Windsurf,ws-browser,Postman-Agent,DBeaverData,Chrome,Old FirefoxData"
FILES_EXTENSIONS="vhdx,jtl,log"

case "${1:-}" in
  --help|-h)
    cat <<EOF
Usage: $(basename "$0") [DRIVE...] | --cron [DRIVE...] | --help

  (no args)       Scan /mnt/c, write today's baseline snapshot under folder-sizes/c/, and
                  report day-over-day growth.
  DRIVE...        WSL drive letters to scan (e.g. "$(basename "$0") c e" scans /mnt/c and
                  /mnt/e). Defaults to c. Each drive keeps its own folder-sizes/<drive>/
                  snapshots.
  --cron [DRIVE...]
                  Install one midnight crontab entry per drive (defaults to c), each scanning
                  only that drive and appending its console output to folder-sizes/<drive>/
                  cron.log. Per drive: warns and leaves it unchanged if its job already exists,
                  so "--cron c" then "--cron e" registers both.
  --help          Show this help.
EOF
    exit 0
    ;;
  --cron)
    # One single-drive midnight job per drive, each logging beside its own snapshots; the .tsv
    # baseline is still written by --baseline during the run, untouched here.
    shift
    for DRIVE in ${*:-c}; do
      DRIVE_DIR="$BASELINE_DIR/$DRIVE"
      LOG="$DRIVE_DIR/cron.log"
      # Keying the duplicate guard on the per-drive log path lets each drive register independently.
      if crontab -l 2>/dev/null | grep -qF "$LOG"; then
        echo "Warning: a cron job for drive $DRIVE already exists; leaving it unchanged:" >&2
        crontab -l 2>/dev/null | grep -F "$LOG" >&2
        continue
      fi
      mkdir -p "$DRIVE_DIR"
      # mkdir runs before the redirect's target is opened, so a missing folder-sizes/<drive>/ (e.g.
      # fresh checkout) does not make cron fail before the scan can recreate it.
      ENTRY="0 0 * * * mkdir -p \"$DRIVE_DIR\" && \"$SELF\" $DRIVE >> \"$LOG\" 2>&1"
      { crontab -l 2>/dev/null || true; echo "$ENTRY"; } | crontab -
      echo "Installed midnight cron job for drive $DRIVE: $SELF $DRIVE"
      echo "  console output -> $LOG"
    done
    exit 0
    ;;
esac

# Timestamp banner delimits runs in cron.log (stdout is redirected there by the cron entry); it also
# prints to the console on manual runs.
printf '\n\n'
echo "===== folders-growth run: $(date '+%Y-%m-%d %H:%M:%S %Z') ====="

# WSL drive letters to scan under /mnt, taken verbatim from the args (defaults to c). Each drive
# writes its own dated <date>.tsv snapshots under folder-sizes/<drive>/, so same-day snapshots
# never collide and growth is diffed per drive.
DRIVES="${*:-c}"

scan() { java -jar "$JAR" --exclude="$EXCLUDE" --file-extensions="$FILES_EXTENSIONS" "$@"; }

for DRIVE in $DRIVES; do
  ROOT="/mnt/$DRIVE"
  echo "----- scanning $ROOT/ -----"
  scan "$ROOT/" --consumer=folders --min-size-recursive=50MB --baseline="$BASELINE_DIR/$DRIVE"
  # scan "$ROOT/" --consumer=filemanager --min-size=100MB --sort=date
  # Windows user profiles live only on C; report their large folders separately.
  if [ "$DRIVE" = c ]; then
    scan "$ROOT/Users" --consumer=folders --min-size-recursive=1GB
  fi
done