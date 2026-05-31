#!/usr/bin/env bash
set -euo pipefail
# Resolve project root (this script lives in scripts/) so the repo is the git context
cd "$(dirname "$0")/.."

bump="${1:-}"
case "$bump" in ""|--minor|--major) ;; *) echo "Usage: $0 [--minor|--major]" >&2; exit 2 ;; esac

# The v* tags on origin are the single source of released versions, so the newest one is the build
# counter. Reading from origin (not local tags) means an un-fetched clone still sees the true
# latest. --refs drops the peeled ^{} lines annotated tags emit; sort -V orders numerically so
# tail picks the real latest regardless of push order. Empty until the first release.
last=$(git ls-remote --tags --refs origin 'v*' | sed -E 's#.*/##' | sort -V | tail -n1)

if [ -n "$last" ]; then
    IFS=. read -r major minor patch <<<"${last#v}"
    case "$bump" in
        "")      patch=$((patch + 1)) ;;
        --minor) minor=$((minor + 1)) ;;
        --major) major=$((major + 1)); minor=0 ;;
    esac
else
    # First release seeds at 1.0.0 rather than incrementing a non-existent value.
    case "$bump" in
        "")      major=1; minor=0; patch=0 ;;
        --minor) major=1; minor=1; patch=0 ;;
        --major) major=2; minor=0; patch=0 ;;
    esac
fi

version="$major.$minor.$patch"
tag="v$version"
echo "→ next version: $version (last: ${last:-none})"

git tag -a "$tag" -m "$tag"
if ! git push origin "$tag"; then
    # Drop the local tag so the burned name does not block a retry after a failed push.
    git tag -d "$tag" >/dev/null
    echo "push failed; removed local tag $tag" >&2
    exit 1
fi
echo "→ released $tag"
