#!/usr/bin/env bash
# Merge the exact green dev commit into testing without discarding prior promotion history.
set -euo pipefail

dev_sha="${1:-${GITHUB_SHA:-}}"
remote="${2:-origin}"

if [[ ! "$dev_sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "error: dev SHA must be a lowercase 40-character commit id" >&2
    exit 1
fi

git fetch "$remote" dev testing

remote_dev="$(git rev-parse "refs/remotes/$remote/dev")"
if [[ "$dev_sha" != "$remote_dev" ]]; then
    echo "error: green dev SHA $dev_sha is stale; current dev is $remote_dev" >&2
    exit 1
fi

git config user.name "qkt promotion"
git config user.email "qkt-promotion@users.noreply.github.com"
git switch --force-create qkt-testing-promotion "refs/remotes/$remote/testing"
git merge --no-ff --no-edit -m "chore(ci): promote dev to testing" "$dev_sha"
git push "$remote" HEAD:refs/heads/testing
