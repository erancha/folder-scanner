# folder-scanner

A CLI utility that walks a directory tree in parallel and feeds every file to a pluggable consumer.
The producer (scanner) and the consumer run on separate thread pools
connected by a bounded queue — when the queue fills, the producer blocks, so the
in-flight buffer stays flat regardless of tree size. Total heap then depends on the
consumer: `aggregate` keeps only bounded per-bucket aggregates, while `duplicates` and
`filemanager` retain one entry per surviving file (O(files)). Three consumers are
currently available:

- `Aggregator` : counts and bytes per extension, size bucket, and date bucket.
- `Duplicate-Locator` : finds identical-content files and writes a shell script that
  quarantines them (or, with `--hard-delete`, removes them outright).
- `File-Manager` : lists (`--action=list`, the default) or deletes
  (`--action=delete`) the files surviving the producer filters; deletion writes a
  shell script that quarantines them (or, with `--hard-delete`, removes them).

```mermaid
flowchart LR
    FS["FolderScanner<br/><br/>(producer)"] --> Q[/"BlockingQueue&lt;FileInfo&gt;<br/><br/>(bounded)"/]
    Q --> C["Aggregator OR Duplicate-Locator OR File-Manager<br/><br/>(one selected per run)"]
```

The scanner (producer) is agnostic of which consumer it feeds. Exactly one consumer
runs per invocation (selected by `--consumer`); the chosen one picks its own
`FileInfo` variant and its drainer count (consumer threads). Per-consumer pipeline
details live in their source files.

## Quick start

Requires Java 21 and Maven on PATH.

```bash
./scripts/start.sh --build                                                 # one-time: mvn clean package
./scripts/start.sh --exclude=.git,target                                   # aggregates the current folder
./scripts/start.sh --consumer=duplicates --exclude=.git,target /mnt/c      # locates duplicates in /mnt/c
./scripts/start.sh --consumer=filemanager --file-extensions=tmp,log \
  --min-size=10MB --exclude=.git,target /mnt/c                             # lists large tmp/log files
./scripts/start.sh --consumer=filemanager --action=delete \
  --file-extensions=tmp --exclude=.git,target /mnt/c                       # writes a script to quarantine them
./scripts/start.sh --help                                                  # all flags
```

**Producer-side filters**

`--exclude=LIST`, `--min-size=SIZE`, and `--file-extensions=LIST` are producer-side filters — filtered-out entries
never enter the bounded queue. `--exclude` is the cheapest because it short-circuits whole subtrees before any
directory listing; the other two cost one attribute check per file.

**Recommended exclude list** for `/mnt/c` (WSL → Windows drive)

```bash
./scripts/start.sh --consumer=duplicates --min-size=1MB --hard-delete \
  --exclude="Windows,ProgramData,Program Files,Program Files (x86),\$Recycle.Bin,System Volume Information,\
workspaceStorage,extensions,.idea,\
.git,node_modules,target,.mvn,build,dist,.gradle,bin,\
EBWebView,WebviewCacheX64,webview2_user_data,cef_cache,WidevineCdm,component_crx_cache,\
AmazonQ,puppeteer,.nuget" \
  /mnt/c
```

**Runtime knobs**

`scripts/start.sh --help` lists every flag. The producer pool defaults higher than the consumer pool because
directory walking is IO-bound — extra producer threads stay productive while others wait on the disk.
To retune for a specific tree, run `./scripts/benchmarks.sh --combinations` (or `--combinations-q` to sweep queue
implementations too) — it walks a grid of producer/consumer/queue configurations and prints throughput for each.

## Deletion safety

Deletion is two-phase: the scan decides which files to remove and bakes their paths into the generated script,
but the actual `rm`/`mv` runs only when **you** execute that script later. The script does not re-hash or re-check
size/mtime at run time, so there is a time-of-check/time-of-use (TOCTOU) gap: if the tree changes between scan and
execution — a kept file edited, a path recreated with different content — the script can delete bytes that no
longer match what the scan saw.

This is bounded, not eliminated, by design. Every generated script embeds its creation time and re-checks it at
run time: if more than ten minutes have passed, it prints the elapsed minutes as a warning before prompting, so a
forgotten script run hours later is hard to miss. On top of that, inspect the script before running it, the
hard-delete path requires typing `DELETE` in capitals to proceed, and each duplicate group leaves the survivor as
a commented `# KEPT` line so you can confirm the keeper. To keep the window small, run a generated script promptly
and avoid mutating the scanned tree in between.
