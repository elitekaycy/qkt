#!/usr/bin/env bash
# Pin release/vX.Y.Z at a chosen commit and print the branch name.
#
#   pin-release-branch.sh SHA      (GITHUB_REPOSITORY defaults to the current gh repo; GH_BIN to gh)
#
# The commit must be an ancestor of testing (or testing itself) and VERSION at it names the release. The branch is created, or
# moved to the commit when it already exists, so a retry for a newer candidate retargets an open promotion PR. paper-soak,
# windows-ci and the promotion PR all run from this branch, which is why testing can move on while a release is attested.
set -euo pipefail
gh_bin="${GH_BIN:-gh}"
repo="${GITHUB_REPOSITORY:-"{owner}/{repo}"}"
sha="${1:-}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "pin-release-branch: SHA must be a lowercase 40-character commit id" >&2; exit 2; }
status="$($gh_bin api "repos/$repo/compare/$sha...testing" --jq .status)"
case "$status" in
    ahead|identical) ;;
    *) echo "pin-release-branch: $sha is not an ancestor of testing (compare says '$status')" >&2; exit 1 ;;
esac
version="$($gh_bin api "repos/$repo/contents/VERSION?ref=$sha" --jq .content | base64 -d | tr -d '\r\n')"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]] || { echo "pin-release-branch: VERSION at $sha is not a version: '$version'" >&2; exit 1; }
branch="release/v$version"
current="$($gh_bin api "repos/$repo/git/ref/heads/$branch" --jq .object.sha 2>/dev/null || true)"
if [ -z "$current" ]; then
    $gh_bin api -X POST "repos/$repo/git/refs" -f "ref=refs/heads/$branch" -f "sha=$sha" > /dev/null
    echo "pin-release-branch: created $branch at $sha" >&2
elif [ "$current" != "$sha" ]; then
    $gh_bin api -X PATCH "repos/$repo/git/refs/heads/$branch" -f "sha=$sha" -F force=true > /dev/null
    echo "pin-release-branch: moved $branch from $current to $sha" >&2
fi
echo "$branch"
