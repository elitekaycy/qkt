#!/usr/bin/env bash
# scripts/precheck.sh — run local checks before pushing.
# Usage: ./scripts/precheck.sh
# Failing any check exits non-zero; CI and human reviewers expect a clean run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

step() {
    printf '\n\033[1;34m==> %s\033[0m\n' "$1"
}

ok() {
    printf '\033[1;32m✓ %s\033[0m\n' "$1"
}

fail() {
    printf '\033[1;31m✗ %s\033[0m\n' "$1" >&2
    exit 1
}

step "Step 1: ./gradlew build"
if ./gradlew build; then
    ok "build passed"
else
    fail "build failed — fix before pushing"
fi

step "Step 2: ./gradlew test (focused)"
if ./gradlew test; then
    ok "tests passed"
else
    fail "tests failed — fix before pushing"
fi

step "Step 3: git working tree is clean"
if [ -z "$(git status --porcelain)" ]; then
    ok "working tree clean"
else
    git status --short
    fail "working tree has uncommitted or untracked changes"
fi

step "Step 4: scan for stray TODO/FIXME without issue links"
if grep -rEn '(TODO|FIXME|XXX)([^#]|$)' src/ 2>/dev/null | grep -v '#[0-9]'; then
    fail "found TODO/FIXME without an issue link (#NNN). Add link or remove."
else
    ok "no unlinked TODO/FIXME"
fi

step "Step 5: scan for AI references in tracked files"
if git grep -nE '(Co-Authored-By:.*Claude|Co-Authored-By:.*GPT|Generated with.*Claude|🤖)' -- ':!scripts/precheck.sh' ':!.claude/' 2>/dev/null; then
    fail "found AI references in tracked files — strip them before pushing"
else
    ok "no AI references in tracked files"
fi

step "Step 6: scan for emojis in source code"
if git grep -lE '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]' -- 'src/**' '*.kt' '*.kts' '*.md' ':!.claude/' ':!scripts/precheck.sh' 2>/dev/null; then
    fail "found emojis in source/docs — remove them"
else
    ok "no emojis in source"
fi

printf '\n\033[1;32mAll precheck steps passed.\033[0m Ready to push.\n'
