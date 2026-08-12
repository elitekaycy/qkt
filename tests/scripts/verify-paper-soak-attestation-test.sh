#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
subject="$repo_root/scripts/verify-paper-soak-attestation.py"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

sha="0123456789abcdef0123456789abcdef01234567"
repository="ghcr.io/test/qkt"
digest="sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
attestation="$tmp_dir/attestation.json"
printf '%s\n' '{"status":"ok"}' > "$tmp_dir/health.jsonl"
printf '%s\n' 'journal evidence' > "$tmp_dir/golden.zip"
printf '%s\n' '{"clean":true}' > "$tmp_dir/reconcile.json"
printf '%s\n' '{"coverage":true}' > "$tmp_dir/coverage.json"
printf '%s\n' '{"parity":true}' > "$tmp_dir/parity.json"
printf '%s\n' '{"insights":true}' > "$tmp_dir/insights.json"
health_sha="$(sha256sum "$tmp_dir/health.jsonl" | cut -d' ' -f1)"
journal_sha="$(sha256sum "$tmp_dir/golden.zip" | cut -d' ' -f1)"
reconcile_sha="$(sha256sum "$tmp_dir/reconcile.json" | cut -d' ' -f1)"
coverage_sha="$(sha256sum "$tmp_dir/coverage.json" | cut -d' ' -f1)"
parity_sha="$(sha256sum "$tmp_dir/parity.json" | cut -d' ' -f1)"
insights_sha="$(sha256sum "$tmp_dir/insights.json" | cut -d' ' -f1)"

write_attestation() {
    local completed_at="$1"
    local unreconciled="$2"
    local attested_sha="${3:-$sha}"
    cat > "$attestation" <<JSON
{
  "schemaVersion": 1,
  "attestationType": "live-parity",
  "testingSha": "$attested_sha",
  "image": "$repository@$digest",
  "accountMode": "demo",
  "canaryStrategy": "ema-canary",
  "startedAtUtc": "2026-08-03T00:00:00Z",
  "completedAtUtc": "$completed_at",
  "tradingDays": 0,
  "status": "pass",
  "metrics": {
    "unreconciledPositions": $unreconciled,
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
}

verify() {
    python3 "$subject" "$attestation" \
        --expected-git-sha "$sha" \
        --expected-image-repository "$repository"
}

write_attestation "2026-08-05T00:00:00Z" 0
verify | grep -q 'paper-soak attestation valid'

verify | grep -q 'live-parity'

write_attestation "2026-08-05T00:00:00Z" 1
if verify > "$tmp_dir/reconcile.out" 2>&1; then
    echo "unreconciled positions must fail closed" >&2
    exit 1
fi
grep -q 'unreconciledPositions must be zero' "$tmp_dir/reconcile.out"

write_attestation "2026-08-05T00:00:00Z" 0 "ffffffffffffffffffffffffffffffffffffffff"
if verify > "$tmp_dir/sha.out" 2>&1; then
    echo "a stale testing SHA must fail closed" >&2
    exit 1
fi
grep -q 'does not match current testing' "$tmp_dir/sha.out"

write_attestation "2026-08-05T00:00:00Z" 0
printf '%s\n' 'tampered' >> "$tmp_dir/golden.zip"
if verify > "$tmp_dir/artifact.out" 2>&1; then
    echo "tampered evidence artifact must fail closed" >&2
    exit 1
fi
grep -q 'journal artifact SHA-256 mismatch' "$tmp_dir/artifact.out"

echo "paper-soak attestation verifier tests passed"
