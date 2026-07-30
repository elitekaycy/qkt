#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

version="$(tr -d '\r\n' < VERSION)"
scripts/verify-version-tag.sh "v$version"

if scripts/verify-version-tag.sh "not-v$version" >/dev/null 2>&1; then
    echo "expected a mismatched release tag to fail" >&2
    exit 1
fi
