#!/usr/bin/env bash
# Fails when a line or branch the change adds or edits under src/main is not covered by tests.
# Reads Kover's XML report (JaCoCo format), so run `./gradlew koverXmlReport` first.
# Usage: check-diff-coverage.sh <compare-ref> [report.xml]
# Env: DIFF_COVERAGE_MIN (default 100); DIFF_COVERAGE_HTML / DIFF_COVERAGE_MARKDOWN (optional report paths).
set -euo pipefail
compare=${1:?usage: check-diff-coverage.sh <compare-ref> [report.xml]}
report=${2:-build/reports/kover/report.xml}
min=${DIFF_COVERAGE_MIN:-100}
[ -f "$report" ] || { echo "::error::no coverage report at $report (run ./gradlew koverXmlReport)"; exit 1; }
command -v diff-cover > /dev/null || { echo "::error::diff-cover missing: pip install -r config/coverage-requirements.txt"; exit 1; }

args=("$report" --compare-branch="$compare" --src-roots src/main/kotlin --include 'src/main/**' --fail-under="$min")
formats=()
[ -n "${DIFF_COVERAGE_HTML:-}" ] && formats+=("html:$DIFF_COVERAGE_HTML")
[ -n "${DIFF_COVERAGE_MARKDOWN:-}" ] && formats+=("markdown:$DIFF_COVERAGE_MARKDOWN")
[ ${#formats[@]} -gt 0 ] && args+=(--format "$(IFS=,; echo "${formats[*]}")")
diff-cover "${args[@]}"
