#!/usr/bin/env bash
# From "testing is green" to a tagged release with nobody at the keyboard:
#
#   attest testing live (run-attestation.sh --dispatch) -> paper-soak green -> promote-to-main ->
#   approve windows-ci -> wait for the promotion PR to be CLEAN on the attested commit ->
#   approve + merge it (merge commit, head pinned) -> tag vX.Y.Z on the merge commit.
#
# Every stage either passes or stops the release with a one-line reason; nothing is retried by
# hand and nothing is skipped. It is safe to run again after a stop: each stage looks at the state
# it finds (an attestation already assembled for this commit is still re-run, because the live
# evidence must belong to this attempt; an open promotion PR is reused; an existing tag stops it).
set -euo pipefail
# This script checks out `testing` in its own clone, and bash reads a script as it runs it: a
# checkout that rewrites this file would change the program mid-flight. Run from a private copy.
if [ -z "${QKT_RELEASE_REPO_ROOT:-}" ]; then
    QKT_RELEASE_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    copy="$(mktemp "${TMPDIR:-/tmp}/release-unattended.XXXXXX")"
    cp "${BASH_SOURCE[0]}" "$copy"
    export QKT_RELEASE_REPO_ROOT
    exec bash "$copy" "$@"
fi
repo_root="$QKT_RELEASE_REPO_ROOT"
trap 'rm -f "${BASH_SOURCE[0]}"' EXIT

usage() {
    cat <<'USAGE'
Usage: release-unattended.sh --profile FILE --key-file FILE [--repo OWNER/NAME] [--wait-for SHA]

  --profile FILE   attestation profile (see scripts/live-validation/attestation-profile.example)
  --key-file FILE  file holding the demo gateway API key (mode 0600; never passed on a command line)
  --repo           GitHub repository (default: the origin remote)
  --wait-for SHA   a dev commit that testing must contain before the release starts (optional)

The version released is the VERSION file of origin/testing. Needs `gh` authenticated with rights to
approve workflow runs and to review and merge the promotion PR, the paper-soak self-hosted runner
online, and a clean checkout of its own (a dedicated worktree: it checks out testing, detached). Writes progress lines to stdout; exits non-zero at the first failed stage.
USAGE
}
log() { printf '%s %s\n' "$(date -u +%T)" "$*"; }
stop() { log "STOPPED: $*"; exit 1; }

profile=""; key_file=""; repo=""; wait_for=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --profile) profile="${2:-}"; shift 2 ;;
        --key-file) key_file="${2:-}"; shift 2 ;;
        --repo) repo="${2:-}"; shift 2 ;;
        --wait-for) wait_for="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; echo "release-unattended: unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "$profile" ] && [ -n "$key_file" ] || { usage >&2; exit 2; }
[ -f "$profile" ] || stop "profile not found: $profile"
[ -f "$key_file" ] || stop "key file not found: $key_file"
[ "$(stat -c %a "$key_file")" = 600 ] || stop "key file must be mode 0600: $key_file"
for tool in gh jq git; do command -v "$tool" > /dev/null || stop "required command not found: $tool"; done
cd "$repo_root"
[ -z "$(git status --porcelain)" ] || stop "checkout is not clean"
[ -n "$repo" ] || repo="$(git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"

# 1. testing: contains the wanted commit, and its own CI is green.
for _ in $(seq 1 90); do
    git fetch -q origin
    testing="$(git rev-parse origin/testing)"
    if [ -n "$wait_for" ] && ! git merge-base --is-ancestor "$wait_for" "$testing" 2> /dev/null; then
        log "testing ${testing:0:8} does not contain ${wait_for:0:8} yet"; sleep 60; continue
    fi
    runs="$(gh run list -R "$repo" --branch testing --limit 12 --json name,status,conclusion,headSha \
        -q ".[] | select(.headSha == \"$testing\") | .name + \":\" + .status + \":\" + (.conclusion // \"\")" | tr '\n' ' ')"
    log "testing ${testing:0:8} [$runs]"
    grep -qE '(check|integration|docker):completed:(failure|cancelled)' <<<"$runs" && stop "testing CI failed on $testing"
    if grep -q 'check:completed:success' <<<"$runs" && grep -q 'integration:completed:success' <<<"$runs" &&
        grep -q 'docker:completed:success' <<<"$runs"; then break; fi
    sleep 60
done
grep -q 'docker:completed:success' <<<"${runs:-}" || stop "testing CI did not finish"
version="v$(git show "$testing:VERSION" | tr -d '\r\n')"
git rev-parse -q --verify "refs/tags/$version" > /dev/null && stop "$version is already tagged; bump VERSION first"
git ls-remote --exit-code --tags origin "$version" > /dev/null 2>&1 && stop "$version is already tagged on origin"
online="$(gh api "repos/$repo/actions/runners" --jq '[.runners[] | select(.status == "online")] | length' 2> /dev/null || echo 0)"
[ "$online" -gt 0 ] || stop "no self-hosted runner is online; paper-soak would queue forever"
log "releasing $version from testing $testing"
git checkout -q --detach "$testing"

# 2. live attestation, which also dispatches paper-soak with the evidence it assembled.
started="$(date +%s)"
QKT_BROKER_API_KEY="$(cat "$key_file")" QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY \
    bash scripts/live-validation/run-attestation.sh --profile "$profile" --dispatch || stop "attestation failed: see its output above"
log "attested in $(( $(date +%s) - started ))s"

# 3. paper-soak on the self-hosted runner.
sleep 30
soak="$(gh run list -R "$repo" --workflow paper-soak.yml --limit 1 --json databaseId -q '.[0].databaseId')"
for _ in $(seq 1 60); do
    state="$(gh run view "$soak" -R "$repo" --json status,conclusion -q '.status + " " + (.conclusion // "")')"
    case "$state" in completed*) break ;; esac; sleep 30
done
[ "$state" = "completed success" ] || stop "paper-soak run $soak: $state"
log "paper-soak $soak green"

# 4. the promotion PR, on exactly the attested commit.
gh workflow run promote-to-main.yml -R "$repo" --ref testing > /dev/null
sleep 40
promote="$(gh run list -R "$repo" --workflow promote-to-main.yml --limit 1 --json databaseId -q '.[0].databaseId')"
for _ in $(seq 1 30); do
    state="$(gh run view "$promote" -R "$repo" --json status,conclusion -q '.status + " " + (.conclusion // "")')"
    case "$state" in completed*) break ;; esac; sleep 20
done
[ "$state" = "completed success" ] || stop "promote-to-main run $promote: $state"
pr="$(gh pr list -R "$repo" --base main --head testing --state open --json number -q '.[0].number')"
[ -n "$pr" ] || stop "promote-to-main opened no PR"
log "promotion PR #$pr"
held="$(gh api "repos/$repo/actions/runs?head_sha=$testing&status=action_required" \
    --jq '.workflow_runs[] | select(.name == "windows-ci") | .id' | head -n 1)"
[ -z "$held" ] || { gh api -X POST "repos/$repo/actions/runs/$held/approve" > /dev/null && log "approved windows-ci run $held"; }
for _ in $(seq 1 60); do
    merge_state="$(gh pr view "$pr" -R "$repo" --json mergeStateStatus -q .mergeStateStatus)"
    [ "$merge_state" = CLEAN ] && break
    sleep 45
done
[ "$merge_state" = CLEAN ] || stop "promotion PR #$pr is $merge_state, not CLEAN"
[ "$(gh pr view "$pr" -R "$repo" --json headRefOid -q .headRefOid)" = "$testing" ] ||
    stop "promotion PR #$pr moved off the attested commit; something was merged to dev during the release"
gh pr review "$pr" -R "$repo" --approve \
    --body "Promotion of testing $testing for $version: unattended live attestation passed, paper-soak green, all required checks green." > /dev/null
gh pr merge "$pr" -R "$repo" --merge --match-head-commit "$testing" > /dev/null
sleep 10
merged="$(gh pr view "$pr" -R "$repo" --json state,mergeCommit -q '.state + " " + (.mergeCommit.oid // "")')"
case "$merged" in MERGED*) ;; *) stop "promotion PR #$pr did not merge: $merged" ;; esac

# 5. the tag, which starts the release and image workflows.
git fetch -q origin
git tag "$version" "${merged#MERGED }"
git push -q origin "$version"
log "RELEASED $version at ${merged#MERGED }"
