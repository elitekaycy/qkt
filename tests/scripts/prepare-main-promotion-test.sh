#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
subject="$repo_root/scripts/prepare-main-promotion.sh"
workflow="$repo_root/.github/workflows/promote-to-main.yml"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

fake_gh="$tmp_dir/gh"
log_file="$tmp_dir/calls.log"

cat > "$fake_gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_GH_LOG"

sha="${FAKE_TIP:-0123456789abcdef0123456789abcdef01234567}"
case "$*" in
    "api repos/test/repo/branches/testing --jq .commit.sha")
        printf '%s\n' "$sha"
        ;;
    "api repos/test/repo/compare/"*"...testing --jq .status")
        printf '%s\n' "${FAKE_RELATION:-ahead}"
        ;;
    "run list --repo test/repo --workflow "*" --branch testing --commit "*" --limit 10 --json conclusion,headSha,url --jq "*)
        workflow="$(sed -E 's/.*--workflow ([^ ]+) .*/\1/' <<< "$*")"
        case " ${FAKE_CI_MISSING:-} " in *" $workflow "*) ;; *) printf 'https://example.test/%s\n' "$workflow" ;; esac
        ;;
    "api repos/test/repo/compare/main..."*" --jq .ahead_by")
        printf '%s\n' "${FAKE_AHEAD_BY:-1}"
        ;;
    "api repos/test/repo/compare/main..."*" --jq .files[].filename")
        printf '%s\n' "${FAKE_CHANGED_FILES:-src/main/kotlin/com/qkt/app/Main.kt}"
        ;;
    "api repos/test/repo/contents/VERSION?ref="*" --jq .content")
        printf '%s\n' "MS4yLjMK"
        ;;
    "api repos/test/repo/git/ref/tags/v1.2.3 --jq .ref")
        [ "${FAKE_TAGGED:-false}" = true ] && echo refs/tags/v1.2.3 || exit 1
        ;;
    "api repos/test/repo/git/ref/heads/release/v1.2.3 --jq .object.sha")
        [ -n "${FAKE_BRANCH_SHA:-}" ] && echo "$FAKE_BRANCH_SHA" || exit 1
        ;;
    "api -X POST repos/test/repo/git/refs -f ref=refs/heads/release/v1.2.3 -f sha="*)
        ;;
    "api -X PATCH repos/test/repo/git/refs/heads/release/v1.2.3 -f sha="*" -F force=true")
        ;;
    "pr list --repo test/repo --base main --head release/v1.2.3 --state open --limit 1 --json url --jq .[0].url // empty")
        printf '%s\n' "${FAKE_PR_URL:-}"
        ;;
    "run list --repo test/repo --workflow windows-ci.yml --commit "*" --limit 1 --json conclusion,headSha,status,url --jq .[0] | [.conclusion, .headSha, .status, .url] | join(\"|\")")
        if [ -n "${FAKE_WINDOWS_SHA:-}" ]; then
            printf '%s|%s|%s|%s\n' \
                "${FAKE_WINDOWS_CONCLUSION:-}" \
                "$FAKE_WINDOWS_SHA" \
                "${FAKE_WINDOWS_STATUS:-completed}" \
                "https://example.test/windows"
        fi
        ;;
    "run list --repo test/repo --workflow paper-soak.yml --limit 30 --json conclusion,databaseId,headBranch,headSha,url --jq "*)
        if [ "${FAKE_SOAK_MISSING:-false}" != "true" ]; then
            printf '%s|%s|%s|%s\n' \
                "77" \
                "${FAKE_SOAK_SHA:-0123456789abcdef0123456789abcdef01234567}" \
                "https://example.test/soak" \
                "${FAKE_SOAK_BRANCH:-release/v1.2.3}"
        fi
        ;;
    run\ download\ 77\ --repo\ test/repo\ --name\ paper-soak-attestation\ --dir\ *)
        out_dir="${@: -1}"
        mkdir -p "$out_dir"
        printf '%s\n' '{"status":"ok"}' > "$out_dir/health.jsonl"
        printf '%s\n' 'journal evidence' > "$out_dir/golden.zip"
        printf '%s\n' '{"clean":true}' > "$out_dir/reconcile.json"
        printf '%s\n' '{"coverage":true}' > "$out_dir/coverage.json"
        printf '%s\n' '{"parity":true}' > "$out_dir/parity.json"
        printf '%s\n' '{"insights":true}' > "$out_dir/insights.json"
        printf '%s\n' '{"schema":"qkt-attestation-trace-v1","parityMismatches":0,"cases":{}}' > "$out_dir/trace.json"
        # A minimal valid catalog evidence; its rules are tested in scripts/live-validation/tests/test_attest_resume.py.
        python3 - "$out_dir/catalog.json" <<'PY'
import hashlib, json, sys
sha = "0123456789abcdef0123456789abcdef01234567"
binding = {"testingSha": sha, "cliVersion": f"qkt 1.0 ({sha})"}
hashed = hashlib.sha256(json.dumps(binding, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
at = "2026-08-03T00:20:00Z"
stage = {"status": "passed", "completedAtUtc": at}
json.dump({"schema": "qkt-attestation-catalog-evidence-v1", "testingSha": sha, "binding": binding, "bindingSha256": hashed,
           "waveRunId": "parity-test-20260803-0001", "assembledAtUtc": at, "maxEvidenceAgeHours": 24,
           "stages": {"proofs": stage, "wave": stage, "insights": stage},
           "lanes": ["orders", "risk", "book", "engine", "daemon", "stress"],
           "cases": [{"name": "orders-a", "status": "passed", "bindingSha256": hashed, "passedAtUtc": at, "flaky": False,
                      "resultSha256": "0" * 64, "attempts": [{"status": "passed"}]}]}, open(sys.argv[1], "w"))
PY
        catalog_sha="$(sha256sum "$out_dir/catalog.json" | cut -d' ' -f1)"
        health_sha="$(sha256sum "$out_dir/health.jsonl" | cut -d' ' -f1)"
        journal_sha="$(sha256sum "$out_dir/golden.zip" | cut -d' ' -f1)"
        reconcile_sha="$(sha256sum "$out_dir/reconcile.json" | cut -d' ' -f1)"
        coverage_sha="$(sha256sum "$out_dir/coverage.json" | cut -d' ' -f1)"
        parity_sha="$(sha256sum "$out_dir/parity.json" | cut -d' ' -f1)"
        insights_sha="$(sha256sum "$out_dir/insights.json" | cut -d' ' -f1)"
        trace_sha="$(sha256sum "$out_dir/trace.json" | cut -d' ' -f1)"
        cat > "$out_dir/paper-soak-attestation.json" <<JSON
{
  "schemaVersion": 1,
  "attestationType": "live-parity",
  "runId": "parity-test-20260803-0001",
  "inputFingerprint": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "testingSha": "0123456789abcdef0123456789abcdef01234567",
  "image": "ghcr.io/test/qkt@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "accountMode": "demo",
  "canaryStrategy": "ema-canary",
  "startedAtUtc": "2026-08-03T00:00:00Z",
  "completedAtUtc": "2026-08-03T00:20:00Z",
  "tradingDays": 0,
  "status": "pass",
  "metrics": {
    "unreconciledPositions": 0,
    "unknownOutcomePlacements": 0,
    "droppedTicks": 0,
    "healthSamples": 20
  },
  "parity": {"durationMinutes": 20, "strategiesTested": 2, "indicatorsTested": 3, "mathScenariosTested": 2, "dslScenariosTested": 2, "orderTypesTested": 2, "totalTicks": 100, "totalBars": 20, "fills": 2, "parityComparisons": 2, "insightsEvents": 10, "warmupBars": 100, "warmupTicks": 200, "barBoundaryTransitions": 2, "timeframesTested": ["1m", "1h", "4h"], "parityMismatches": 0, "unexplainedRejections": 0, "unexplainedOrderOutcomes": 0},
  "artifacts": {
    "health": "health.jsonl",
    "journal": "golden.zip",
    "reconciliation": "reconcile.json",
    "coverage": "coverage.json",
    "parity": "parity.json",
    "insights": "insights.json",
    "catalog": "catalog.json",
    "trace": "trace.json"
  },
  "artifactSha256": {
    "health": "$health_sha",
    "journal": "$journal_sha",
    "reconciliation": "$reconcile_sha",
    "coverage": "$coverage_sha",
    "parity": "$parity_sha",
    "insights": "$insights_sha",
    "catalog": "$catalog_sha",
    "trace": "$trace_sha"
  }
}
JSON
        ;;
    pr\ create*)
        printf '%s\n' "https://example.test/pull/1"
        ;;
    "workflow run windows-ci.yml --repo test/repo --ref release/v1.2.3")
        ;;
    *)
        printf 'unexpected gh call: %s\n' "$*" >&2
        exit 2
        ;;
esac
FAKE_GH
chmod +x "$fake_gh"

run_subject() {
    env \
        GITHUB_REPOSITORY=test/repo \
        GH_BIN="$fake_gh" \
        FAKE_GH_LOG="$log_file" \
        "$@" \
        bash "$subject"
}

output="$(run_subject)"
grep -q 'promotion PR: https://example.test/pull/1' <<< "$output"
grep -q 'paper-soak attestation valid' <<< "$output"
grep -q '^pr create ' "$log_file"
grep -q '^workflow run windows-ci.yml ' "$log_file"

: > "$log_file"
output="$(run_subject FAKE_PR_URL=https://example.test/pull/7)"
grep -q 'promotion PR: https://example.test/pull/7' <<< "$output"
if grep -q '^pr create ' "$log_file"; then
    echo "existing PR must be reused" >&2
    exit 1
fi

: > "$log_file"
output="$(run_subject \
    FAKE_PR_URL=https://example.test/pull/7 \
    FAKE_WINDOWS_SHA=0123456789abcdef0123456789abcdef01234567 \
    FAKE_WINDOWS_CONCLUSION=success)"
grep -q 'Windows validation already current' <<< "$output"
if grep -q '^workflow run windows-ci.yml ' "$log_file"; then
    echo "current Windows validation must not be duplicated" >&2
    exit 1
fi

: > "$log_file"
output="$(run_subject FAKE_AHEAD_BY=0)"
grep -q 'no promotion PR required' <<< "$output"
if grep -q '^pr list ' "$log_file"; then
    echo "identical branches must not query or create a PR" >&2
    exit 1
fi

: > "$log_file"
if run_subject FAKE_CI_MISSING=integration.yml > "$tmp_dir/stale.out" 2>&1; then
    echo "a candidate without a green integration run must fail closed" >&2
    exit 1
fi
grep -q 'no successful integration.yml run exists for candidate' "$tmp_dir/stale.out"

: > "$log_file"
for missing in check.yml docker.yml; do
    if run_subject FAKE_CI_MISSING="$missing" > "$tmp_dir/failed.out" 2>&1; then
        echo "a candidate without a green $missing run must fail closed" >&2
        exit 1
    fi
    grep -q "no successful $missing run exists for candidate" "$tmp_dir/failed.out"
done

: > "$log_file"
if run_subject FAKE_SOAK_SHA=ffffffffffffffffffffffffffffffffffffffff > "$tmp_dir/stale-soak.out" 2>&1; then
    echo "stale paper soak must fail closed" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for candidate' "$tmp_dir/stale-soak.out"

: > "$log_file"
if run_subject FAKE_SOAK_MISSING=true > "$tmp_dir/missing-soak.out" 2>&1; then
    echo "missing paper soak must fail closed" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for candidate' "$tmp_dir/missing-soak.out"

# Docs-only promotions waive the paper soak: even with no soak run available, a
# promotion whose whole main...testing delta is documentation still opens the PR.
: > "$log_file"
output="$(run_subject \
    FAKE_CHANGED_FILES="$(printf 'docs/get-started/mt5-gateway.md\nREADME.md\nmkdocs.yml')" \
    FAKE_SOAK_MISSING=true)"
grep -q 'promotion PR: https://example.test/pull/1' <<< "$output"
grep -q 'waiving paper soak' <<< "$output"
grep -q '^pr create ' "$log_file"
if grep -q 'workflow paper-soak.yml' "$log_file"; then
    echo "docs-only promotion must not query the paper soak" >&2
    exit 1
fi

# A single non-docs file in the delta reinstates the full soak gate.
: > "$log_file"
if run_subject \
    FAKE_CHANGED_FILES="$(printf 'docs/x.md\nsrc/main/kotlin/com/qkt/app/Main.kt')" \
    FAKE_SOAK_MISSING=true > "$tmp_dir/mixed-soak.out" 2>&1; then
    echo "a non-docs file must reinstate the soak gate" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for candidate' "$tmp_dir/mixed-soak.out"

# The compare API stops listing at 300 files, so a list that long is never trusted as docs-only.
: > "$log_file"
if run_subject \
    FAKE_CHANGED_FILES="$(for i in $(seq 1 300); do echo "docs/page-$i.md"; done)" \
    FAKE_SOAK_MISSING=true > "$tmp_dir/capped-soak.out" 2>&1; then
    echo "a capped file list must not waive the soak" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for candidate' "$tmp_dir/capped-soak.out"

# Promoting a candidate: the tip has moved on, the candidate is an ancestor of testing, and every gate is checked for the
# candidate, not the tip. The PR comes from release/vX.Y.Z pinned at the candidate.
tip=ffffffffffffffffffffffffffffffffffffffff
candidate=0123456789abcdef0123456789abcdef01234567
: > "$log_file"
output="$(run_subject FAKE_TIP=$tip PROMOTE_CANDIDATE=$candidate)"
grep -q 'is an ancestor of testing' <<< "$output"
grep -q 'paper-soak attestation valid' <<< "$output"
grep -q -- "--head release/v1.2.3" "$log_file"
grep -q "^api -X POST repos/test/repo/git/refs -f ref=refs/heads/release/v1.2.3 -f sha=$candidate" "$log_file"
grep -q -- "--ref release/v1.2.3" "$log_file"
if grep -q -- '--head testing\|--ref testing' "$log_file"; then
    echo "a candidate promotion must never use the moving testing branch" >&2
    exit 1
fi
grep -q -- "--commit $candidate" "$log_file"

: > "$log_file"
output="$(run_subject FAKE_TIP=$tip PROMOTE_CANDIDATE=$candidate FAKE_BRANCH_SHA=1111111111111111111111111111111111111111)"
grep -q "^api -X PATCH repos/test/repo/git/refs/heads/release/v1.2.3 -f sha=$candidate -F force=true" "$log_file"

: > "$log_file"
output="$(run_subject FAKE_TIP=$tip PROMOTE_CANDIDATE=$candidate FAKE_BRANCH_SHA=$candidate)"
if grep -q '^api -X \(POST\|PATCH\)' "$log_file"; then
    echo "a branch already at the candidate must be left alone" >&2
    exit 1
fi

: > "$log_file"
if run_subject FAKE_TIP=$tip PROMOTE_CANDIDATE=$candidate FAKE_RELATION=diverged > "$tmp_dir/diverged.out" 2>&1; then
    echo "a candidate that is not an ancestor of testing must fail closed" >&2
    exit 1
fi
grep -q 'is not an ancestor of testing' "$tmp_dir/diverged.out"
if grep -q '^pr create \|^workflow run' "$log_file"; then
    echo "a rejected candidate must open nothing" >&2
    exit 1
fi

: > "$log_file"
if run_subject FAKE_TIP=$tip PROMOTE_CANDIDATE=$candidate FAKE_SOAK_SHA=$tip > "$tmp_dir/other-soak.out" 2>&1; then
    echo "a paper-soak run of the moved tip must not stand for the candidate" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for candidate' "$tmp_dir/other-soak.out"

: > "$log_file"
if run_subject FAKE_SOAK_BRANCH=feature/x > "$tmp_dir/branch-soak.out" 2>&1; then
    echo "a paper-soak run from an arbitrary branch must not count" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for candidate' "$tmp_dir/branch-soak.out"

: > "$log_file"
if run_subject FAKE_TAGGED=true > "$tmp_dir/tagged.out" 2>&1; then
    echo "an existing tag must stop the promotion" >&2
    exit 1
fi
grep -q 'already tagged' "$tmp_dir/tagged.out"

if run_subject PROMOTE_CANDIDATE=not-a-sha > "$tmp_dir/bad.out" 2>&1; then
    echo "a malformed candidate must fail closed" >&2
    exit 1
fi
grep -q 'candidate must be a lowercase 40-character' "$tmp_dir/bad.out"

if ! grep -q '^          ref: testing$' "$workflow"; then
    echo "promotion workflow must checkout the integration-tested testing ref" >&2
    exit 1
fi

grep -q 'PROMOTE_CANDIDATE: ${{ inputs.candidate }}' "$workflow"
echo "prepare-main-promotion tests passed"
