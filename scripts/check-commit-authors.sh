#!/usr/bin/env bash
# Fails when a commit in RANGE was authored or committed as an AI tool, or carries a tool's
# attribution trailer: every commit in this repository is its human author's.
# Usage: check-commit-authors.sh <revision-range>
set -euo pipefail
range=${1:?usage: check-commit-authors.sh <revision-range>}
tools='anthropic|claude|openai|chatgpt|copilot|codex|cursor|gemini'

identities=$(git log --format='%h %an <%ae> / %cn <%ce>' "$range" | grep -iE "$tools" || true)
trailers=$(git log --format='%h %(trailers:only,unfold)' "$range" |
    grep -iE "^[0-9a-f]+ .*(co-authored-by|claude-session):.*($tools)|^[0-9a-f]+ claude-session:" || true)
messages=$(git log --format='%h %s%n%h %b' "$range" | grep -iE "generated (with|by) .*($tools)|🤖" || true)

if [ -n "$identities$trailers$messages" ]; then
    printf '::error::commits must be authored by their human author, without tool attribution:\n'
    printf '%s\n' "$identities" "$trailers" "$messages" | sed '/^$/d'
    exit 1
fi
echo "commit authors: ok ($(git rev-list --count "$range") commits)"
