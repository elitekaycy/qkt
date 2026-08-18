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

case "$*" in
    "api repos/test/repo/branches/testing --jq .commit.sha")
        printf '%s\n' "0123456789abcdef0123456789abcdef01234567"
        ;;
    "run list --repo test/repo --workflow integration.yml --branch testing --limit 1 --json conclusion,headSha,url --jq .[0] | [.conclusion, .headSha, .url] | join(\"|\")")
        printf '%s|%s|%s\n' \
            "${FAKE_INTEGRATION_CONCLUSION:-success}" \
            "${FAKE_INTEGRATION_SHA:-0123456789abcdef0123456789abcdef01234567}" \
            "https://example.test/integration"
        ;;
    "api repos/test/repo/compare/main...testing --jq .ahead_by")
        printf '%s\n' "${FAKE_AHEAD_BY:-1}"
        ;;
    "api repos/test/repo/compare/main...testing --jq .files[].filename")
        printf '%s\n' "${FAKE_CHANGED_FILES:-src/main/kotlin/com/qkt/app/Main.kt}"
        ;;
    "pr list --repo test/repo --base main --head testing --state open --limit 1 --json url --jq .[0].url // empty")
        printf '%s\n' "${FAKE_PR_URL:-}"
        ;;
    "run list --repo test/repo --workflow windows-ci.yml --branch testing --limit 1 --json conclusion,headSha,status,url --jq .[0] | [.conclusion, .headSha, .status, .url] | join(\"|\")")
        if [ -n "${FAKE_WINDOWS_SHA:-}" ]; then
            printf '%s|%s|%s|%s\n' \
                "${FAKE_WINDOWS_CONCLUSION:-}" \
                "$FAKE_WINDOWS_SHA" \
                "${FAKE_WINDOWS_STATUS:-completed}" \
                "https://example.test/windows"
        fi
        ;;
    "run list --repo test/repo --workflow paper-soak.yml --branch testing --limit 20 --json conclusion,databaseId,headSha,url --jq .[] | select(.conclusion == \"success\") | [.databaseId, .headSha, .url] | join(\"|\")")
        if [ "${FAKE_SOAK_MISSING:-false}" != "true" ]; then
            printf '%s|%s|%s\n' \
                "77" \
                "${FAKE_SOAK_SHA:-0123456789abcdef0123456789abcdef01234567}" \
                "https://example.test/soak"
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
        health_sha="$(sha256sum "$out_dir/health.jsonl" | cut -d' ' -f1)"
        journal_sha="$(sha256sum "$out_dir/golden.zip" | cut -d' ' -f1)"
        reconcile_sha="$(sha256sum "$out_dir/reconcile.json" | cut -d' ' -f1)"
        coverage_sha="$(sha256sum "$out_dir/coverage.json" | cut -d' ' -f1)"
        parity_sha="$(sha256sum "$out_dir/parity.json" | cut -d' ' -f1)"
        insights_sha="$(sha256sum "$out_dir/insights.json" | cut -d' ' -f1)"
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
    "insights": "insights.json"
  },
  "artifactSha256": {
    "health": "$health_sha",
    "journal": "$journal_sha",
    "reconciliation": "$reconcile_sha",
    "coverage": "$coverage_sha",
    "parity": "$parity_sha",
    "insights": "$insights_sha"
  }
}
JSON
        ;;
    pr\ create*)
        printf '%s\n' "https://example.test/pull/1"
        ;;
    "workflow run windows-ci.yml --repo test/repo --ref testing")
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
if run_subject FAKE_INTEGRATION_SHA=stale-sha > "$tmp_dir/stale.out" 2>&1; then
    echo "stale integration must fail closed" >&2
    exit 1
fi
grep -q 'not current testing' "$tmp_dir/stale.out"

: > "$log_file"
if run_subject FAKE_INTEGRATION_CONCLUSION=failure > "$tmp_dir/failed.out" 2>&1; then
    echo "failed integration must fail closed" >&2
    exit 1
fi
grep -q "is 'failure', not 'success'" "$tmp_dir/failed.out"

: > "$log_file"
if run_subject FAKE_SOAK_SHA=ffffffffffffffffffffffffffffffffffffffff > "$tmp_dir/stale-soak.out" 2>&1; then
    echo "stale paper soak must fail closed" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for current testing' "$tmp_dir/stale-soak.out"

: > "$log_file"
if run_subject FAKE_SOAK_MISSING=true > "$tmp_dir/missing-soak.out" 2>&1; then
    echo "missing paper soak must fail closed" >&2
    exit 1
fi
grep -q 'no successful paper-soak run exists for current testing' "$tmp_dir/missing-soak.out"

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
grep -q 'no successful paper-soak run exists for current testing' "$tmp_dir/mixed-soak.out"

if ! grep -q '^          ref: testing$' "$workflow"; then
    echo "promotion workflow must checkout the integration-tested testing ref" >&2
    exit 1
fi

echo "prepare-main-promotion tests passed"
