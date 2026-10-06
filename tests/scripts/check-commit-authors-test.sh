#!/usr/bin/env bash
# A tool-authored or tool-committed commit, or a tool attribution trailer, fails the author check;
# commits by their human author pass.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp" && git init -q && git config user.name 'Dickson Anyaele' && git config user.email dev@example.com
git commit -q --allow-empty -m 'chore(build): base'
base=$(git rev-parse HEAD)
check() { bash "$repo_root/scripts/check-commit-authors.sh" "$base..HEAD" > /dev/null 2>&1; }

git commit -q --allow-empty -m 'fix(app): a human change'
check || { echo "FAIL a human commit was refused"; exit 1; }
echo "ok commits by their human author pass"

expect_refused() {  # label
    if check; then echo "FAIL $1 was accepted"; exit 1; fi
    echo "ok $1 is refused"
    git reset -q --hard HEAD~1
}
git -c user.name=Claude -c user.email=noreply@anthropic.com commit -q --allow-empty -m 'fix(app): tool authored'
expect_refused "a tool-authored commit"
GIT_COMMITTER_NAME=Claude GIT_COMMITTER_EMAIL=noreply@anthropic.com git commit -q --allow-empty -m 'fix(app): tool committed'
expect_refused "a tool-committed commit"
git commit -q --allow-empty -m 'fix(app): co-authored' -m 'Co-Authored-By: Claude <noreply@anthropic.com>'
expect_refused "a tool co-author trailer"
git commit -q --allow-empty -m 'fix(app): session' -m 'Claude-Session: https://claude.ai/code/session_x'
expect_refused "a tool session trailer"
