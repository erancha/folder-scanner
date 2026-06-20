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
Usage: $(basename "$0") [--cron|--help]

  (no args)  Scan /mnt/c, write today's baseline snapshot under folder-sizes/, and list
             large files by date.
  --cron     Install a crontab entry running this script every midnight, appending its
             console output to folder-sizes/cron.log. Warns and does nothing if already set.
  --help     Show this help.
EOF
    exit 0
    ;;
  --cron)
    # Re-run this script at midnight, appending its console output to cron.log beside the snapshots;
    # the .tsv baseline is still written by --baseline, untouched here. One job per script only.
    if crontab -l 2>/dev/null | grep -qF "$SELF"; then
      echo "Warning: a cron job for this script already exists; leaving it unchanged:" >&2
      crontab -l 2>/dev/null | grep -F "$SELF" >&2
      exit 0
    fi
    mkdir -p "$BASELINE_DIR"
    LOG="$BASELINE_DIR/cron.log"
    # mkdir runs before the redirect's target is opened, so a missing folder-sizes/ (e.g. fresh
    # checkout) does not make cron fail before the scan can recreate it.
    ENTRY="0 0 * * * mkdir -p \"$BASELINE_DIR\" && \"$SELF\" >> \"$LOG\" 2>&1"
    { crontab -l 2>/dev/null || true; echo "$ENTRY"; } | crontab -
    echo "Installed midnight cron job: $SELF"
    echo "  console output -> $LOG"
    exit 0
    ;;
esac

# Timestamp banner delimits runs in cron.log (stdout is redirected there by the cron entry); it also
# prints to the console on manual runs.
printf '\n\n'
echo "===== folders-growth run: $(date '+%Y-%m-%d %H:%M:%S %Z') ====="

# WSL mount of the Windows C: drive — the shell counterpart of the .cmd's c:/ root.
scan() { java -jar "$JAR" --exclude="$EXCLUDE" --file-extensions="$FILES_EXTENSIONS" /mnt/c/ "$@"; }

scan --consumer=folders --min-size-recursive=50MB --baseline="$BASELINE_DIR"
# scan --consumer=filemanager --min-size=100MB --sort=date
