#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

git init --bare --initial-branch=dev "$tmp/remote.git" >/dev/null
git clone "$tmp/remote.git" "$tmp/work" >/dev/null 2>&1
cd "$tmp/work"
git config user.name test
git config user.email test@example.com

printf 'base\n' > app.txt
git add app.txt
git commit -m 'test(ci): add base' >/dev/null
git push origin dev >/dev/null 2>&1
base_sha="$(git rev-parse HEAD)"

git switch -c testing >/dev/null 2>&1
git commit --allow-empty -m 'chore(ci): promote dev to testing' >/dev/null
git push origin testing >/dev/null 2>&1
old_testing="$(git rev-parse HEAD)"

git switch dev >/dev/null 2>&1
printf 'next\n' >> app.txt
git commit -am 'fix(ci): add next change' >/dev/null
git push origin dev >/dev/null 2>&1
dev_sha="$(git rev-parse HEAD)"

if git push origin "$dev_sha:refs/heads/testing" >/dev/null 2>&1; then
    echo 'expected the old fast-forward promotion to fail' >&2
    exit 1
fi

bash "$repo_root/scripts/promote-dev-to-testing.sh" "$dev_sha" origin >/dev/null
git fetch origin dev testing >/dev/null 2>&1
promoted="$(git rev-parse origin/testing)"

test "$(git show -s --format=%s "$promoted")" = 'chore(ci): promote dev to testing'
test "$(git rev-parse "$promoted^1")" = "$old_testing"
test "$(git rev-parse "$promoted^2")" = "$dev_sha"
test "$(git rev-parse "$promoted^{tree}")" = "$(git rev-parse "$dev_sha^{tree}")"
git merge-base --is-ancestor "$base_sha" "$promoted"

git switch --detach "$dev_sha" >/dev/null 2>&1
printf 'newest\n' >> app.txt
git commit -am 'fix(ci): add newest change' >/dev/null
newest_sha="$(git rev-parse HEAD)"
git push origin "$newest_sha:refs/heads/dev" >/dev/null 2>&1

if bash "$repo_root/scripts/promote-dev-to-testing.sh" "$dev_sha" origin >"$tmp/stale.out" 2>&1; then
    echo 'expected stale promotion to fail' >&2
    exit 1
fi
grep -F "green dev SHA $dev_sha is stale; current dev is $newest_sha" "$tmp/stale.out" >/dev/null
