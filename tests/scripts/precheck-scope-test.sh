#!/usr/bin/env bash
# Scope detection of scripts/precheck.sh: which diffs run the fast Gradle checks, which skip Gradle, and that the
# detection works from a git worktree against PRECHECK_BASE. Needs neither the JDK nor Gradle.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
precheck="$repo_root/scripts/precheck.sh"

expect() {
    local want="$1" got
    got="$(printf '%s' "$2" | bash "$precheck" --scope - 2>/dev/null)"
    if [ "$got" != "$want" ]; then
        printf 'expected %s, got %s for:\n%s\n' "$want" "$got" "$2" >&2
        exit 1
    fi
}

# Documentation nothing reads skips Gradle, the same rule CI uses.
expect docs-only 'docs/contributing/development-setup.md'
expect docs-only "$(printf 'README.md\nCHANGELOG.md\ndocs/research/2026-11-16-x.md\n')"

# Sources, build logic, config and Gradle files run the fast Gradle checks, even beside docs.
expect gradle 'src/main/kotlin/com/qkt/cli/Main.kt'
expect gradle 'src/test/kotlin/com/qkt/parity/WallClockSourcePolicyTest.kt'
expect gradle "$(printf 'README.md\nsrc/main/kotlin/com/qkt/cli/Main.kt\n')"
expect gradle 'build-logic/src/main/kotlin/qkt.file-size.gradle.kts'
expect gradle 'build.gradle.kts'
expect gradle 'settings.gradle.kts'
expect gradle 'gradle/libs.versions.toml'
expect gradle 'gradlew'
expect gradle 'config/file-size-baseline.txt'
expect gradle 'VERSION'
# Docs a policy test reads (CliDocsSyncTest) or the DSL reference blocks.
expect gradle 'docs/reference/cli-commands.md'
expect gradle 'docs/reference/dsl/indicators.md'

# Scripts, workflows and script tests need no Gradle.
expect other 'scripts/precheck.sh'
expect other "$(printf 'scripts/precheck.sh\ntests/scripts/precheck-scope-test.sh\nAGENTS.md\n')"
expect other '.github/workflows/check.yml'
expect other 'attestation/README.md'

# An empty diff fails safe to Gradle.
expect gradle ''
expect gradle "$(printf '\n\n')"

# Every policy test the fast mode names exists, so a rename cannot make `test --tests` fail for everyone.
grep -oE '^    com\.qkt\.[A-Za-z.]+Test$' "$precheck" | while read -r fqcn; do
    test -f "$repo_root/src/test/kotlin/$(printf '%s' "$fqcn" | tr . /).kt" || {
        echo "precheck names a missing policy test: $fqcn" >&2
        exit 1
    }
done
test "$(grep -cE '^    com\.qkt\.[A-Za-z.]+Test$' "$precheck")" -ge 4

# Scope from a real diff, run from a linked worktree, with PRECHECK_BASE as the base.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git init -q -b dev "$work/repo"
mkdir -p "$work/repo/scripts"
cp "$precheck" "$repo_root/scripts/classify-ci-changes.sh" "$work/repo/scripts/"
g() { git -C "$1" -c user.name=t -c user.email=t@example.com "${@:2}"; }
g "$work/repo" add -A
g "$work/repo" commit -qm base
g "$work/repo" worktree add -q -b topic "$work/wt"

scope_in_wt() { (cd "$work/wt" && PRECHECK_BASE=dev bash scripts/precheck.sh --scope 2>/dev/null); }

echo notes > "$work/wt/NOTES.md"
g "$work/wt" add -A
g "$work/wt" commit -qm docs
test "$(scope_in_wt)" = docs-only

mkdir -p "$work/wt/src/main/kotlin"
echo 'fun main() {}' > "$work/wt/src/main/kotlin/Main.kt"
g "$work/wt" add -A
g "$work/wt" commit -qm src
test "$(scope_in_wt)" = gradle

# Called by absolute path from elsewhere, it still resolves the worktree that holds it.
test "$(cd / && PRECHECK_BASE=dev bash "$work/wt/scripts/precheck.sh" --scope 2>/dev/null)" = gradle

# A missing base checks everything.
test "$(cd "$work/wt" && PRECHECK_BASE=no-such-ref bash scripts/precheck.sh --scope 2>/dev/null)" = gradle

# The pre-push hook runs this script.
grep -Fq 'scripts/precheck.sh' "$repo_root/.githooks/pre-push"

echo "precheck scope: ok"
