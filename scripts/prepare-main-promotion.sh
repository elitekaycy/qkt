#!/usr/bin/env bash
# Verify a candidate commit of testing and prepare its review-gated promotion PR to main.

set -euo pipefail

repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
gh_bin="${GH_BIN:-gh}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The release names a candidate: a commit already on testing, normally the one that was attested. Testing is free to move on
# while it is attested, so nothing here reads "the tip" except to default the candidate to it (the old behaviour).
usage() { echo "Usage: prepare-main-promotion.sh [--candidate SHA]   (or PROMOTE_CANDIDATE=SHA; default: the current testing tip)" >&2; }
candidate="${PROMOTE_CANDIDATE:-}"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --candidate) candidate="${2:-}"; shift 2 ;;
        *) usage; echo "::error::unknown argument: $1" >&2; exit 2 ;;
    esac
done
testing_tip="$($gh_bin api "repos/$repo/branches/testing" --jq '.commit.sha')"
candidate="${candidate:-$testing_tip}"
if [[ ! "$candidate" =~ ^[0-9a-f]{40}$ ]]; then
    echo "::error::candidate must be a lowercase 40-character commit id, got '$candidate'"
    exit 1
fi
if [ "$candidate" != "$testing_tip" ]; then
    relation="$($gh_bin api "repos/$repo/compare/$candidate...testing" --jq '.status')"
    case "$relation" in
        ahead|identical) echo "candidate $candidate is an ancestor of testing ($testing_tip)" ;;
        *) echo "::error::candidate $candidate is not an ancestor of testing $testing_tip (compare says '$relation')"; exit 1 ;;
    esac
fi
testing_sha="$candidate"

# Every CI gate, for the candidate itself: a green run on some other commit proves nothing about it.
green_run() {  # workflow -> url of a successful run of the candidate on testing, or nothing
    $gh_bin run list \
        --repo "$repo" \
        --workflow "$1" \
        --branch testing \
        --commit "$candidate" \
        --limit 10 \
        --json conclusion,headSha,url \
        --jq ".[] | select(.headSha == \"$candidate\" and .conclusion == \"success\") | .url" | head -n 1
}
integration_url=""
for workflow in check.yml integration.yml docker.yml; do
    url="$(green_run "$workflow")"
    if [ -z "$url" ]; then
        echo "::error::no successful $workflow run exists for candidate $candidate on testing"
        exit 1
    fi
    [ "$workflow" != integration.yml ] || integration_url="$url"
done

ahead_by="$($gh_bin api "repos/$repo/compare/main...testing" --jq '.ahead_by')"
if [ "$ahead_by" = "0" ]; then
    echo "main already contains candidate $candidate; no promotion PR required"
    exit 0
fi

# Docs-only fast lane: when every file the testing->main promotion carries is
# documentation (docs/, any *.md, mkdocs.yml, requirements-docs.txt), the change
# cannot alter runtime behavior, so the exact-image paper soak — release-grade
# evidence tied to a versioned engine build — is not applicable and is waived.
# The required CI checks (build, integration, runtime-smoke, build-and-push,
# build-windows) still run on testing and still gate the promotion PR; only the
# manual soak/attestation step is skipped. Any non-docs file flips this off and
# the full soak gate below applies unchanged.
docs_only=true
changed_files="$($gh_bin api "repos/$repo/compare/main...testing" --jq '.files[].filename')"
# The compare API lists at most 300 files; a list that long may hide code files, so no waiver.
if [ -z "$changed_files" ] || [ "$(wc -l <<< "$changed_files")" -ge 300 ]; then
    docs_only=false
fi
while IFS= read -r changed_file; do
    [ -z "$changed_file" ] && continue
    case "$changed_file" in
        docs/*|*.md|mkdocs.yml|requirements-docs.txt) : ;;
        *) docs_only=false; break ;;
    esac
done <<< "$changed_files"

if [ "$docs_only" = true ]; then
    echo "docs-only promotion for $candidate: every changed file is documentation; waiving paper soak"
    soak_url="waived — docs-only promotion (no runtime change)"
    soak_image="n/a — docs-only promotion"
    attestation_type="docs-only (soak waived)"
else

# paper-soak runs on testing or on the release branch pinned at the candidate; the run must be for the candidate itself.
soak_runs="$($gh_bin run list \
    --repo "$repo" \
    --workflow paper-soak.yml \
    --limit 30 \
    --json conclusion,databaseId,headBranch,headSha,url \
    --jq '.[] | select(.conclusion == "success") | [.databaseId, .headSha, .url, .headBranch] | join("|")')"
soak_id=""
soak_url=""
while IFS='|' read -r candidate_id candidate_sha candidate_url candidate_branch; do
    if [ -n "$candidate_id" ] && [ "$candidate_sha" = "$candidate" ] &&
        { [ "$candidate_branch" = testing ] || [[ "$candidate_branch" == release/* ]]; }; then
        soak_id="$candidate_id"
        soak_url="$candidate_url"
        break
    fi
done <<< "$soak_runs"
if [ -z "$soak_id" ]; then
    echo "::error::no successful paper-soak run exists for candidate $candidate"
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
    --expected-git-sha "$candidate" \
    --repo-root "$PWD" \
    --expected-image-repository "ghcr.io/${repo%%/*}/qkt"
soak_image="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["image"])' "$soak_attestation")"
attestation_type="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("attestationType", "paper-soak"))' "$soak_attestation")"

fi

# The promotion PR comes from release/vX.Y.Z, pinned at the candidate, never from testing. An existing tag stops it.
version="$($gh_bin api "repos/$repo/contents/VERSION?ref=$candidate" --jq '.content' | base64 -d | tr -d '\r\n')"
if $gh_bin api "repos/$repo/git/ref/tags/v$version" --jq '.ref' > /dev/null 2>&1; then
    echo "::error::v$version is already tagged; bump VERSION first"
    exit 1
fi
release_branch="$(GH_BIN="$gh_bin" GITHUB_REPOSITORY="$repo" bash "$script_dir/pin-release-branch.sh" "$candidate")"

pr_url="$($gh_bin pr list \
    --repo "$repo" \
    --base main \
    --head "$release_branch" \
    --state open \
    --limit 1 \
    --json url \
    --jq '.[0].url // empty')"

if [ -z "$pr_url" ]; then
    pr_body="$(printf '%s\n' \
        '## Summary' \
        '' \
        "Promote tested commit \`$candidate\` (v$version) from \`testing\` to \`main\`, through \`$release_branch\` pinned at it." \
        '' \
        '## Changes' \
        '' \
        '- carry the attested testing commit through the protected main promotion path' \
        '- testing may have moved on; later commits are not part of this promotion' \
        '' \
        '## Tests' \
        '' \
        "- testing integration: $integration_url" \
        '- Windows packaging and installer validation is dispatched for the candidate' \
        "- exact-image $attestation_type evidence: $soak_url" \
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
        --head "$release_branch" \
        --title "chore(ci): promote testing to main (v$version)" \
        --body "$pr_body")"
fi

echo "promotion PR: $pr_url"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf 'Promotion PR: %s\n' "$pr_url" >> "$GITHUB_STEP_SUMMARY"
fi

windows_run="$($gh_bin run list \
    --repo "$repo" \
    --workflow windows-ci.yml \
    --commit "$candidate" \
    --limit 1 \
    --json conclusion,headSha,status,url \
    --jq '.[0] | [.conclusion, .headSha, .status, .url] | join("|")')"

windows_current=false
if [ -n "$windows_run" ]; then
    IFS='|' read -r windows_conclusion windows_sha windows_status windows_url <<< "$windows_run"
    if [ "$windows_sha" = "$candidate" ] &&
        { [ "$windows_conclusion" = "success" ] || [ "$windows_status" = "queued" ] || [ "$windows_status" = "in_progress" ]; }; then
        windows_current=true
        echo "Windows validation already current: $windows_url"
    fi
fi

if [ "$windows_current" = false ]; then
    $gh_bin workflow run windows-ci.yml --repo "$repo" --ref "$release_branch"
    echo "dispatched Windows validation for $candidate"
fi
