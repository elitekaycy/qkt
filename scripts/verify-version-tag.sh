#!/usr/bin/env bash
set -euo pipefail

tag="${1:-${GITHUB_REF_NAME:-}}"
if [[ -z "$tag" ]]; then
    echo "usage: verify-version-tag.sh <vX.Y.Z>" >&2
    exit 2
fi

version="$(tr -d '\r\n' < VERSION)"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "VERSION is not a semantic version: $version" >&2
    exit 1
fi

expected="v$version"
if [[ "$tag" != "$expected" ]]; then
    echo "release tag $tag does not match VERSION $version (expected $expected)" >&2
    exit 1
fi

echo "release tag $tag matches VERSION $version"
