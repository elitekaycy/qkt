#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
classify="$repo_root/scripts/classify-ci-changes.sh"
workflow="$repo_root/.github/workflows/check.yml"

expect() {
    local want="$1" got
    got="$(printf '%s' "$2" | bash "$classify" 2>/dev/null)"
    if [ "$got" != "$want" ]; then
        printf 'expected %s, got %s for:\n%s\n' "$want" "$got" "$2" >&2
        exit 1
    fi
}

# Documentation nothing reads skips the build.
expect docs-only 'docs/contributing/test-coverage.md'
expect docs-only "$(printf 'README.md\ndocs/concepts/backtest-model.md\nCHANGELOG.md\nmkdocs.yml\n')"
expect docs-only "$(printf 'docs/research/2026-11-16-x.md\n.claude/skills/qkt/SKILL.md')"

# Any code, build, config, script or test path runs the full build, even beside docs.
expect full 'src/main/kotlin/com/qkt/app/Main.kt'
expect full "$(printf 'docs/concepts/backtest-model.md\nsrc/main/kotlin/com/qkt/app/Main.kt\n')"
expect full 'src/test/resources/fixture.md'
expect full 'build.gradle.kts'
expect full 'build-logic/src/main/kotlin/qkt.coverage.gradle.kts'
expect full 'gradle/libs.versions.toml'
expect full 'config/coverage-floor.properties'
expect full 'config/coverage-requirements.txt'
expect full 'scripts/check-diff-coverage.sh'
expect full 'scripts/release-proof/README.md'
expect full 'tests/data-e2e/README.md'
expect full 'verification/oracle/engine/README.md'
expect full 'attestation/README.md'
expect full 'examples/README.md'
expect full 'strategies/README.md'
expect full 'editor/README.md'
expect full '.github/workflows/check.yml'
expect full '.github/pull_request_template.md'
expect full 'VERSION'
expect full 'gradlew'

# Docs that tests or the build read are code for this purpose.
expect full 'docs/reference/dsl/indicators.md'
expect full 'docs/reference/dsl-grammar.md'
expect full 'docs/reference/cli-commands.md'
expect full 'docs/reference/bot-cli.md'
expect full 'docs/examples/ema-cross.md'
expect full 'docs/tutorials/compose-portfolio.md'
expect full 'docs/how-to/backtest-data.md'
expect full 'docs/index.md'
expect full 'docs/assets/logo-icon.svg'

# Empty, unknown and git-quoted paths fail safe.
expect full ''
expect full "$(printf '\n\n')"
expect full 'LICENSE'
expect full 'Dockerfile'
expect full '"docs/caf\303\251.md"'

# Every literal docs path a test or build script names classifies as full, so a new docs reader cannot be skipped silently.
readers="$(
    {
        grep -rhoE 'Path\.of\("docs"(, "[^"]+")*\)' "$repo_root/src/test" \
            | sed -E 's/^Path\.of\(//; s/\)$//; s/", "/\//g; s/"//g'
        grep -rhoE '(Path\.of|file|dir)\("docs/[^"$]+"' "$repo_root/src/test" "$repo_root/build-logic" "$repo_root/build.gradle.kts" \
            | sed -E 's/^[A-Za-z.]+\("//; s/"$//'
    } | sort -u
)"
test -n "$readers"
while IFS= read -r reader; do
    case "$reader" in
        *.*) expect full "$reader" ;;
        *) expect full "$reader/page.md" ;;
    esac
done <<< "$readers"

# check.yml skips only on pull requests and gates the gradle steps on the classifier.
grep -Fq 'bash scripts/classify-ci-changes.sh' "$workflow"
# Both check jobs (`build` and `scripts`) classify inside the job, never with a job-level `if:` that would skip a required check.
test "$(grep -Fc 'if [ "$EVENT_NAME" != pull_request ]; then' "$workflow")" -eq 2
test "$(grep -Ec '^  (build|scripts):$' "$workflow")" -eq 2
# A merge-queue run is the combined result about to land on dev: it is not a pull_request event, so it never skips.
grep -Eq '^  merge_group:$' "$workflow"
test "$(grep -Fc "if: github.event_name == 'pull_request' && steps.changes.outputs.full == 'true'" "$workflow")" -eq 1
test "$(grep -Fc 'run: ./gradlew build koverXmlReport koverHtmlReport --no-daemon' "$workflow")" -eq 1

echo "classify-ci-changes: all checks passed"
