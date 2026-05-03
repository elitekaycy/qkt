#!/usr/bin/env bash
# scripts/install-hooks.sh — wire git to use the repo's .githooks directory.
# Run once after cloning. Idempotent.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ ! -d .githooks ]; then
    echo "install-hooks: .githooks directory missing — are you at the repo root?" >&2
    exit 1
fi

chmod +x .githooks/* 2>/dev/null || true

git config core.hooksPath .githooks

echo "Hooks installed. core.hooksPath = $(git config --get core.hooksPath)"
echo "Active hooks:"
ls -1 .githooks
