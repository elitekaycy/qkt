#!/usr/bin/env bash
# Verify testing and prepare its review-gated promotion PR to main.

set -euo pipefail

repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
gh_bin="${GH_BIN:-gh}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

testing_sha="$($gh_bin api "repos/$repo/branches/testing" --jq '.commit.sha')"
integration="$($gh_bin run list \
    --repo "$repo" \
    --workflow integration.yml \
    --branch testing \
    --limit 1 \
    --json conclusion,headSha,url \
    --jq '.[0] | [.conclusion, .headSha, .url] | join("|")')"

if [ -z "$integration" ]; then
    echo "::error::no integration run exists for testing"
    exit 1
fi

IFS='|' read -r integration_conclusion integration_sha integration_url <<< "$integration"
if [ "$integration_sha" != "$testing_sha" ]; then
    echo "::error::latest testing integration is for $integration_sha, not current testing $testing_sha"
    exit 1
fi
if [ "$integration_conclusion" != "success" ]; then
    echo "::error::testing integration for $testing_sha is '$integration_conclusion', not 'success'"
    exit 1
fi

ahead_by="$($gh_bin api "repos/$repo/compare/main...testing" --jq '.ahead_by')"
if [ "$ahead_by" = "0" ]; then
    echo "main already contains every testing commit; no promotion PR required"
    exit 0
fi

soak_runs="$($gh_bin run list \
    --repo "$repo" \
    --workflow paper-soak.yml \
    --branch testing \
    --limit 20 \
    --json conclusion,databaseId,headSha,url \
    --jq '.[] | select(.conclusion == "success") | [.databaseId, .headSha, .url] | join("|")')"
soak_id=""
soak_url=""
while IFS='|' read -r candidate_id candidate_sha candidate_url; do
    if [ -n "$candidate_id" ] && [ "$candidate_sha" = "$testing_sha" ]; then
        soak_id="$candidate_id"
        soak_url="$candidate_url"
        break
    fi
done <<< "$soak_runs"
if [ -z "$soak_id" ]; then
    echo "::error::no successful paper-soak run exists for current testing $testing_sha"
    exit 1
fi

soak_dir="$(mktemp -d)"
trap 'rm -rf "$soak_dir"' EXIT
$gh_bin run download "$soak_id" \
    --repo "$repo" \
    --name paper-soak-attestation \
    --dir "$soak_dir"
soak_attestation="$soak_dir/paper-soak-attestation.json"
python3 "$script_dir/verify-paper-soak-attestation.py" "$soak_attestation" \
    --expected-git-sha "$testing_sha" \
    --expected-image-repository "ghcr.io/${repo%%/*}/qkt"
soak_image="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["image"])' "$soak_attestation")"

pr_url="$($gh_bin pr list \
    --repo "$repo" \
    --base main \
    --head testing \
    --state open \
    --limit 1 \
    --json url \
    --jq '.[0].url // empty')"

if [ -z "$pr_url" ]; then
    pr_body="$(printf '%s\n' \
        '## Summary' \
        '' \
        "Promote tested commit \`$testing_sha\` from \`testing\` to \`main\`." \
        '' \
        '## Changes' \
        '' \
        '- carry the current testing branch through the protected main promotion path' \
        '- preserve the reviewed commits and their testing evidence' \
        '' \
        '## Tests' \
        '' \
        "- testing integration: $integration_url" \
        '- Windows packaging and installer validation is dispatched for the testing head' \
        "- exact-image paper soak: $soak_url" \
        "- soaked image: \`$soak_image\`" \
        '' \
        '## Documentation' \
        '' \
        '- promote documentation already reviewed with the underlying changes' \
        '' \
        '## Compatibility' \
        '' \
        '- no additional source changes are introduced by this promotion PR' \
        '' \
        '## Out Of Scope' \
        '' \
        '- unrelated work not already present on testing' \
        '' \
        '## Risk Notes' \
        '' \
        '- release risk is defined by the underlying testing commits, exact-image paper soak, and their PRs')"
    pr_url="$($gh_bin pr create \
        --repo "$repo" \
        --base main \
        --head testing \
        --title 'chore(ci): promote testing to main' \
        --body "$pr_body")"
fi

echo "promotion PR: $pr_url"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf 'Promotion PR: %s\n' "$pr_url" >> "$GITHUB_STEP_SUMMARY"
fi

windows_run="$($gh_bin run list \
    --repo "$repo" \
    --workflow windows-ci.yml \
    --branch testing \
    --limit 1 \
    --json conclusion,headSha,status,url \
    --jq '.[0] | [.conclusion, .headSha, .status, .url] | join("|")')"

windows_current=false
if [ -n "$windows_run" ]; then
    IFS='|' read -r windows_conclusion windows_sha windows_status windows_url <<< "$windows_run"
    if [ "$windows_sha" = "$testing_sha" ] &&
        { [ "$windows_conclusion" = "success" ] || [ "$windows_status" = "queued" ] || [ "$windows_status" = "in_progress" ]; }; then
        windows_current=true
        echo "Windows validation already current: $windows_url"
    fi
fi

if [ "$windows_current" = false ]; then
    $gh_bin workflow run windows-ci.yml --repo "$repo" --ref testing
    echo "dispatched Windows validation for $testing_sha"
fi
