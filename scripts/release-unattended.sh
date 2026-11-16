#!/usr/bin/env bash
# From "testing is green" to a tagged release with nobody at the keyboard:
#
#   attest a candidate commit of testing live (run-attestation.sh --dispatch) -> paper-soak green -> promote-to-main ->
#   approve windows-ci -> wait for the promotion PR to be CLEAN on the attested commit ->
#   approve + merge it (merge commit, head pinned) -> tag vX.Y.Z on the merge commit.
#
# Every stage either passes or stops the release with a one-line reason; nothing is retried by
# hand and nothing is skipped. It is safe to run again after a stop: each stage looks at the state
# it finds (on the same commit the attestation keeps what passed on this exact build and reruns the rest, see
# scripts/live-validation/lib/attest_resume.py; an open promotion PR is reused; an existing tag stops it).
#
# A release names a CANDIDATE (--candidate SHA, default the testing tip when it starts). Everything below is for that commit:
# CI, attestation, paper-soak, Windows validation, the promotion PR (release/vX.Y.Z pinned at it) and the merge. Testing and
# dev may move on meanwhile; a candidate that is not an ancestor of testing stops the release.
set -euo pipefail
# This script checks out `testing` in its own clone, and bash reads a script as it runs it: a
# checkout that rewrites this file would change the program mid-flight. Run from a private copy.
# The copy is marked by its own path, so only the private copy ever deletes itself: a preset
# QKT_RELEASE_REPO_ROOT can never make the real script remove itself.
if [ "${QKT_RELEASE_PRIVATE_COPY:-}" != "${BASH_SOURCE[0]}" ]; then
    QKT_RELEASE_REPO_ROOT="${QKT_RELEASE_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
    QKT_RELEASE_PRIVATE_COPY="$(mktemp "${TMPDIR:-/tmp}/release-unattended.XXXXXX")"
    cp "${BASH_SOURCE[0]}" "$QKT_RELEASE_PRIVATE_COPY"
    export QKT_RELEASE_REPO_ROOT QKT_RELEASE_PRIVATE_COPY
    exec bash "$QKT_RELEASE_PRIVATE_COPY" "$@"
fi
repo_root="$QKT_RELEASE_REPO_ROOT"
trap 'rm -f "$QKT_RELEASE_PRIVATE_COPY"' EXIT

usage() {
    cat <<'USAGE'
Usage: release-unattended.sh --profile FILE --key-file FILE [--repo OWNER/NAME] [--wait-for SHA] [--candidate SHA] [--no-carry]

  --profile FILE   attestation profile (see scripts/live-validation/attestation-profile.example)
  --key-file FILE  file holding the demo gateway API key (mode 0600; never passed on a command line)
  --repo           GitHub repository (default: the origin remote)
  --wait-for SHA   a dev commit that testing must contain before the release starts (optional)
  --candidate SHA  the testing commit to release (default: the testing tip when the release starts). It must be an ancestor of
                   testing and green on check, integration and docker; testing may move on while it is attested.
  --no-carry       attest every case on the candidate even when an earlier commit with the same runtime fingerprint passed it
                   (the scheduled full re-attestation that audits the carry rules)

  QKT_RELEASE_TESTING_POLLS  minutes to wait for testing to contain it and go green (default 90)

The version released is the VERSION file of the candidate. Needs `gh` authenticated with rights to
approve workflow runs and to review and merge the promotion PR, the paper-soak self-hosted runner
online, and a clean checkout of its own (a dedicated worktree: it checks out testing, detached). Writes progress lines to stdout; exits non-zero at the first failed stage.
USAGE
}
log() { printf '%s %s\n' "$(date -u +%T)" "$*"; }
stop() { log "STOPPED: $*"; exit 1; }

profile=""; key_file=""; repo=""; wait_for=""; candidate=""; no_carry=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --profile) profile="${2:-}"; shift 2 ;;
        --key-file) key_file="${2:-}"; shift 2 ;;
        --repo) repo="${2:-}"; shift 2 ;;
        --wait-for) wait_for="${2:-}"; shift 2 ;;
        --candidate) candidate="${2:-}"; shift 2 ;;
        --no-carry) no_carry="--no-carry"; shift ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; echo "release-unattended: unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "$profile" ] && [ -n "$key_file" ] || { usage >&2; exit 2; }
[ -z "$candidate" ] || [[ "$candidate" =~ ^[0-9a-f]{40}$ ]] || { usage >&2; echo "release-unattended: --candidate must be a lowercase 40-character commit id" >&2; exit 2; }
[ -f "$profile" ] || stop "profile not found: $profile"
[ -f "$key_file" ] || stop "key file not found: $key_file"
[ "$(stat -c %a "$key_file")" = 600 ] || stop "key file must be mode 0600: $key_file"
for tool in gh jq git; do command -v "$tool" > /dev/null || stop "required command not found: $tool"; done
cd "$repo_root"
[ -z "$(git status --porcelain)" ] || stop "checkout is not clean"
[ -n "$repo" ] || repo="$(git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"

# Everything that can already be known wrong, before waiting on CI. Market hours are not a start gate: the attestation's
# own preflight plans the session lanes into their windows once it knows when it starts, waits for them in process, and
# stops only when a window is further away than the profile's session_wait_max_h.
if [ -f scripts/live-validation/lib/attest_preflight.py ]; then
    preflight_log="$(mktemp "${TMPDIR:-/tmp}/qkt-release-preflight.XXXXXX")"
    QKT_BROKER_API_KEY="$(cat "$key_file")" QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY \
        python3 scripts/live-validation/lib/attest_preflight.py --profile "$profile" --repo-root "$repo_root" \
        --key-file "$key_file" --dispatch --no-session > "$preflight_log" 2>&1 ||
        { cat "$preflight_log"; stop "preflight: $(tail -n 1 "$preflight_log" | cut -c1-900)"; }
    log "$(tail -n 1 "$preflight_log")"; rm -f "$preflight_log"
fi

# 1. testing: contains the wanted commit, and its own CI is green.
for _ in $(seq 1 "$((${QKT_RELEASE_TESTING_POLLS:-90} * 2))"); do
    git fetch -q origin
    testing="$(git rev-parse origin/testing)"
    if [ -n "$candidate" ]; then
        # The candidate, not the tip: testing moving on changes nothing here, but the candidate must be part of testing.
        git cat-file -e "$candidate^{commit}" 2> /dev/null || stop "candidate ${candidate:0:8} is not in this clone"
        git merge-base --is-ancestor "$candidate" "$testing" 2> /dev/null || stop "candidate ${candidate:0:8} is not an ancestor of testing ${testing:0:8}"
        testing="$candidate"
    elif [ -n "$wait_for" ] && ! git merge-base --is-ancestor "$wait_for" "$testing" 2> /dev/null; then
        log "testing ${testing:0:8} does not contain ${wait_for:0:8} yet"; sleep 30; continue
    fi
    runs="$(gh run list -R "$repo" --branch testing --commit "$testing" --json name,status,conclusion,headSha \
        -q ".[] | select(.headSha == \"$testing\") | .name + \":\" + .status + \":\" + (.conclusion // \"\")" | tr '\n' ' ')"
    log "candidate ${testing:0:8} [$runs]"
    if grep -qE '(check|integration|docker):completed:(failure|cancelled)' <<<"$runs"; then
        # A job can fail for a third party's outage (the Gradle distribution download returned 5xx twice
        # on 2026-09-21). Nobody is here to press "re-run", so each failed run gets exactly one.
        [ "${reran:-}" = "$testing" ] && stop "CI failed on candidate $testing, again after one re-run"
        reran="$testing"
        gh run list -R "$repo" --branch testing --commit "$testing" --json databaseId,conclusion,headSha \
            -q ".[] | select(.headSha == \"$testing\" and (.conclusion == \"failure\" or .conclusion == \"cancelled\")) | .databaseId" |
            while read -r failed; do gh run rerun "$failed" -R "$repo" --failed > /dev/null && log "re-ran failed testing run $failed once"; done
        sleep 30
        continue
    fi
    if grep -q 'check:completed:success' <<<"$runs" && grep -q 'integration:completed:success' <<<"$runs" &&
        grep -q 'docker:completed:success' <<<"$runs"; then break; fi
    sleep 30
done
# The loop also ends when its wait runs out: every testing run must be green, not only the last checked.
for run in check integration docker; do
    grep -q "$run:completed:success" <<<"${runs:-}" || stop "testing CI did not finish ($run not green on ${testing:0:8})"
done
version="v$(git show "$testing:VERSION" | tr -d '\r\n')"
git rev-parse -q --verify "refs/tags/$version" > /dev/null && stop "$version is already tagged; bump VERSION first"
git ls-remote --exit-code --tags origin "$version" > /dev/null 2>&1 && stop "$version is already tagged on origin"
online="$(gh api "repos/$repo/actions/runners" --jq '[.runners[] | select(.status == "online")] | length' 2> /dev/null || echo 0)"
[ "$online" -gt 0 ] || stop "no self-hosted runner is online; paper-soak would queue forever"
log "releasing $version from candidate $testing (testing is at $(git rev-parse --short=8 origin/testing))"
git checkout -q --detach "$testing"

# 2. live attestation, which also dispatches paper-soak with the evidence it assembled.
started="$(date +%s)"
QKT_BROKER_API_KEY="$(cat "$key_file")" QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY \
    bash scripts/live-validation/run-attestation.sh --profile "$profile" --dispatch ${no_carry:+"$no_carry"} ||
    stop "attestation failed: $(jq -r .detail "$(. "$profile"; echo "$wave_root")/attestation-run.json" 2>/dev/null | cut -c1-1000)"
log "attested in $(( $(date +%s) - started ))s"

# 3. paper-soak on the self-hosted runner.
# A dispatched run takes seconds to appear: asked for until it does, not after a fixed wait.
newest_run() {  # workflow, earliest creation time: an older run of this commit is not the one just dispatched
    local id=""
    for _ in $(seq 1 36); do
        id="$(gh run list -R "$repo" --workflow "$1" --commit "$testing" --limit 5 --json databaseId,createdAt \
            -q "[.[] | select(.createdAt >= \"$2\")][0].databaseId // empty")"
        [ -z "$id" ] || break
        sleep 5
    done
    printf '%s' "$id"
}
soak="$(newest_run paper-soak.yml "$(date -u -d "@$((started - 60))" +%FT%TZ)")"; [ -n "$soak" ] || stop "paper-soak was dispatched but no run appeared"
for _ in $(seq 1 180); do
    state="$(gh run view "$soak" -R "$repo" --json status,conclusion -q '.status + " " + (.conclusion // "")')"
    case "$state" in completed*) break ;; esac; sleep 10
done
[ "$state" = "completed success" ] || stop "paper-soak run $soak: $state"
log "paper-soak $soak green"

# 4. the promotion PR, on exactly the attested commit.
dispatched="$(date -u -d '-60 seconds' +%FT%TZ)"
gh workflow run promote-to-main.yml -R "$repo" --ref testing -f candidate="$testing" > /dev/null
promote="$(newest_run promote-to-main.yml "$dispatched")"; [ -n "$promote" ] || stop "promote-to-main was dispatched but no run appeared"
for _ in $(seq 1 60); do
    state="$(gh run view "$promote" -R "$repo" --json status,conclusion -q '.status + " " + (.conclusion // "")')"
    case "$state" in completed*) break ;; esac; sleep 10
done
[ "$state" = "completed success" ] || stop "promote-to-main run $promote: $state"
release_branch="release/$version"
pr="$(gh pr list -R "$repo" --base main --head "$release_branch" --state open --json number -q '.[0].number')"
[ -n "$pr" ] || stop "promote-to-main opened no PR"
log "promotion PR #$pr"
held="$(gh api "repos/$repo/actions/runs?head_sha=$testing&status=action_required" \
    --jq '.workflow_runs[] | select(.name == "windows-ci") | .id' | head -n 1)"
[ -z "$held" ] || { gh api -X POST "repos/$repo/actions/runs/$held/approve" > /dev/null && log "approved windows-ci run $held"; }
for _ in $(seq 1 135); do
    merge_state="$(gh pr view "$pr" -R "$repo" --json mergeStateStatus -q .mergeStateStatus)"
    [ "$merge_state" = CLEAN ] && break
    sleep 20
done
[ "$merge_state" = CLEAN ] || stop "promotion PR #$pr is $merge_state, not CLEAN"
[ "$(gh pr view "$pr" -R "$repo" --json headRefOid -q .headRefOid)" = "$testing" ] ||
    stop "promotion PR #$pr is not on the attested candidate ${testing:0:8}"
gh pr review "$pr" -R "$repo" --approve \
    --body "Promotion of candidate $testing for $version: unattended live attestation passed, paper-soak green, all required checks green." > /dev/null
gh pr merge "$pr" -R "$repo" --merge --match-head-commit "$testing" > /dev/null
for _ in $(seq 1 12); do
    merged="$(gh pr view "$pr" -R "$repo" --json state,mergeCommit -q '.state + " " + (.mergeCommit.oid // "")')"
    case "$merged" in MERGED\ ?*) break ;; esac; sleep 5
done
merged="$(gh pr view "$pr" -R "$repo" --json state,mergeCommit -q '.state + " " + (.mergeCommit.oid // "")')"
case "$merged" in MERGED*) ;; *) stop "promotion PR #$pr did not merge: $merged" ;; esac

# 5. the tag, which starts the release and image workflows.
git fetch -q origin
git tag "$version" "${merged#MERGED }"
git push -q origin "$version"
log "RELEASED $version at ${merged#MERGED }"
