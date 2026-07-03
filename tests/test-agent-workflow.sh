#!/usr/bin/env bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/agent-workflow.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    [[ "$haystack" == *"$needle"* ]] || fail "expected output to contain: $needle"
}

chmod +x "$SCRIPT"
bash -n "$SCRIPT"

help_output="$("$SCRIPT" --help)"
assert_contains "$help_output" 'Mutating commands are dry-run by default.'

issue_output="$(
    "$SCRIPT" issue-create \
        --title 'Reject invalid route' \
        --priority P0 \
        --effort simple \
        --type bug \
        --problem 'A live order can use a paper fallback.' \
        --solution 'Reject an order when no route exists.' \
        --backlog 'live execution audit'
)"
assert_contains "$issue_output" 'DRY RUN: issue not created'
assert_contains "$issue_output" '## Problem'
assert_contains "$issue_output" 'effort: simple'
assert_contains "$issue_output" 'Backlog: `docs/backlog.md` — live execution audit'

if "$SCRIPT" issue-create \
    --title 'Bad issue' \
    --priority urgent \
    --effort simple \
    --type bug \
    --problem x \
    --solution y \
    --backlog z >"$TMP/invalid.out" 2>&1; then
    fail 'invalid priority was accepted'
fi
assert_contains "$(<"$TMP/invalid.out")" '--priority must be P0, P1, P2, or P3'

mkdir -p "$TMP/bin"
cat >"$TMP/bin/gh" <<'EOF'
#!/usr/bin/env bash
cat <<'JSON'
[
  {"number":12,"title":"Moderate P0","labels":[{"name":"bug"},{"name":"P0"},{"name":"effort: moderate"}],"updatedAt":"2026-07-02T00:00:00Z","url":"https://example/12"},
  {"number":11,"title":"Simple P2","labels":[{"name":"bug"},{"name":"P2"},{"name":"effort: simple"}],"updatedAt":"2026-07-02T00:00:00Z","url":"https://example/11"},
  {"number":10,"title":"Simple P0","labels":[{"name":"bug"},{"name":"P0"},{"name":"effort: simple"}],"updatedAt":"2026-07-02T00:00:00Z","url":"https://example/10"}
]
JSON
EOF
chmod +x "$TMP/bin/gh"

bugs_output="$(PATH="$TMP/bin:$PATH" "$SCRIPT" bugs)"
expected_order=$'P0\tsimple\t#10'
first_line="${bugs_output%%$'\n'*}"
[[ "$first_line" == "$expected_order"* ]] || fail 'bugs were not sorted by priority and effort'
assert_contains "$bugs_output" $'P2\tsimple\t#11'

git init -q -b fix-agent-test "$TMP/repo"
git -C "$TMP/repo" config user.name Test
git -C "$TMP/repo" config user.email test@example.com
printf 'safe\n' >"$TMP/repo/change.txt"
git -C "$TMP/repo" add change.txt

commit_output="$(
    QKT_REPO_ROOT="$TMP/repo" \
        "$SCRIPT" commit --message 'chore(scripts): test safe commit'
)"
assert_contains "$commit_output" 'DRY RUN: commit not created'
if git -C "$TMP/repo" rev-parse --verify HEAD >/dev/null 2>&1; then
    fail 'dry-run created a commit'
fi

git -C "$TMP/repo" branch -m dev
if QKT_REPO_ROOT="$TMP/repo" \
    "$SCRIPT" commit --message 'chore(scripts): test protected branch' \
    >"$TMP/protected.out" 2>&1; then
    fail 'commit on protected branch was accepted'
fi
assert_contains "$(<"$TMP/protected.out")" 'refusing to commit directly on protected branch'

printf 'agent workflow tests passed\n'
