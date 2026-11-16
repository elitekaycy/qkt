#!/usr/bin/env bash
# scripts/precheck.sh — the local pre-push gate (.githooks/pre-push runs it; install with scripts/install-hooks.sh).
#
# Usage: scripts/precheck.sh [--full | --scope [-]]
#
#   (default)  FAST: catches the failures that cost a CI round trip for nothing — ktlint, the file-size
#              ratchet, main/test compile errors and the source-policy tests — in one Gradle invocation.
#              Target: under ~3 minutes with a warm build dir. CI still runs the full suite.
#   --full     the old pipeline: full `./gradlew build` and `./gradlew test`.
#   --scope    print the scope (docs-only | gradle | other) of this branch's diff and exit.
#   --scope -  print the scope of the paths given one per line on stdin and exit (used by the tests).
#
# Scope comes from `git diff --name-only $PRECHECK_BASE...HEAD` (default base origin/dev):
#   docs-only  every path is documentation no test reads (scripts/classify-ci-changes.sh, the CI rule): no Gradle
#   gradle     sources, build logic, config, Gradle files, docs that tests read, or an empty/unknown diff: Gradle
#   other      scripts, workflows, tests/scripts and the like: no Gradle, changed shell scripts get `bash -n`
#
# When /var/tmp/qkt-work/jvm.lock exists (the shared-laptop rule, PRECHECK_JVM_LOCK overrides), Gradle runs under
# `flock` with --no-daemon and a timeout (PRECHECK_TIMEOUT seconds, default 1800). Works from any git worktree.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
cd "$REPO_ROOT"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$1"; }
skip() { printf '\033[1;33m~ %s\033[0m\n' "$1"; }
fail() { printf '\033[1;31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

# Source-policy tests that scan the source tree, take seconds, and fail often enough to be worth a local run.
POLICY_TESTS=(
    com.qkt.parity.WallClockSourcePolicyTest
    com.qkt.connectivity.ConnectivityArchitectureTest
    com.qkt.cli.CalendarResolutionArchitectureTest
    com.qkt.observe.insights.InsightsContractTypesTest
    com.qkt.cli.CliDocsSyncTest
)

# Paths whose change means Gradle must compile or lint something.
is_gradle_path() {
    case "$1" in
        src/*|build-logic/*|config/*|gradle/*|gradlew|gradlew.bat|*.gradle.kts|VERSION) return 0 ;;
        docs/reference/dsl/*|docs/reference/dsl-grammar.md|docs/reference/cli-commands.md|docs/reference/bot-cli.md) return 0 ;;
    esac
    return 1
}

# Reads changed paths on stdin, prints docs-only | gradle | other.
scope_of() {
    local paths
    paths="$(cat)"
    if [ "$(printf '%s\n' "$paths" | bash "$REPO_ROOT/scripts/classify-ci-changes.sh" 2>/dev/null)" = docs-only ]; then
        echo docs-only
        return
    fi
    if [ -z "$(printf '%s' "$paths" | tr -d '[:space:]')" ]; then
        echo gradle # nothing to compare against: fail safe
        return
    fi
    local path
    while IFS= read -r path; do
        [ -z "$path" ] && continue
        if is_gradle_path "$path"; then
            echo gradle
            return
        fi
    done <<< "$paths"
    echo other
}

changed_paths() {
    local base="${PRECHECK_BASE:-origin/dev}"
    if git rev-parse -q --verify "$base^{commit}" >/dev/null 2>&1; then
        git diff --name-only "$base"...HEAD
    else
        echo "precheck: base $base not found; checking everything" >&2
    fi
}

mode=fast
case "${1:-}" in
    "") ;;
    --full) mode=full ;;
    --scope)
        if [ "${2:-}" = - ]; then scope_of; else changed_paths | scope_of; fi
        exit 0
        ;;
    *) fail "unknown argument: $1 (usage: scripts/precheck.sh [--full | --scope [-]])" ;;
esac

# Runs ./gradlew with the given arguments, under the shared JVM lock when there is one. Output goes to the
# terminal and to $gradle_log so a failure can be mapped to its fix.
gradle_log="$(mktemp "${TMPDIR:-/tmp}/qkt-precheck.XXXXXX")"
trap 'rm -f "$gradle_log"' EXIT
run_gradle() {
    local lock="${PRECHECK_JVM_LOCK:-/var/tmp/qkt-work/jvm.lock}"
    local cmd=(./gradlew "$@")
    if [ -e "$lock" ]; then
        echo "precheck: waiting for the shared JVM lock $lock"
        cmd=(flock "$lock" timeout -k 20 "${PRECHECK_TIMEOUT:-1800}" ./gradlew --no-daemon "$@")
    fi
    "${cmd[@]}" 2>&1 | tee "$gradle_log"
    return "${PIPESTATUS[0]}"
}

# Prints the command that fixes each failed Gradle task found in $gradle_log.
explain_gradle_failure() {
    printf '\n\033[1;31mHow to fix:\033[0m\n'
    local hinted=false
    if grep -qE "task ':ktlint" "$gradle_log"; then
        echo "  ktlint:        ./gradlew ktlintFormat   (then commit the reformatted files)"
        hinted=true
    fi
    if grep -qE "task ':checkFileSize'" "$gradle_log"; then
        echo "  file size:     split the files listed above under 200 lines (tests 220); after shrinking a"
        echo "                 baselined file run ./gradlew updateFileSizeBaseline"
        hinted=true
    fi
    if grep -qE "task ':compile(Test)?Kotlin'" "$gradle_log"; then
        echo "  compile:       fix the 'e: file://...' errors above, then ./gradlew compileKotlin compileTestKotlin"
        hinted=true
    fi
    if grep -qE "task ':test'" "$gradle_log"; then
        echo "  policy tests:  read the failure above, then ./gradlew test --tests <TestClass>"
        hinted=true
    fi
    if grep -qE "exit code 124|Terminated" "$gradle_log"; then
        echo "  timeout:       Gradle exceeded ${PRECHECK_TIMEOUT:-1800}s; raise PRECHECK_TIMEOUT or retry"
        hinted=true
    fi
    $hinted || echo "  read the Gradle output above; rerun with: scripts/precheck.sh"
}

step "Working tree is clean"
if [ -n "$(git status --porcelain)" ]; then
    git status --short
    fail "working tree has uncommitted or untracked changes (the check runs on what you push)"
fi
ok "working tree clean"

changed="$(changed_paths)"
scope="$(printf '%s\n' "$changed" | scope_of)"
started=$SECONDS

if [ "$mode" = full ]; then
    step "Full Gradle build and tests (--full)"
    run_gradle build || { explain_gradle_failure; fail "build failed"; }
    run_gradle test || { explain_gradle_failure; fail "tests failed"; }
    ok "build and tests passed"
elif [ "$scope" = gradle ]; then
    step "Fast Gradle checks: ktlint, file size, compile, source-policy tests"
    test_args=()
    for t in "${POLICY_TESTS[@]}"; do test_args+=(--tests "$t"); done
    if run_gradle ktlintCheck checkFileSize compileKotlin compileTestKotlin test "${test_args[@]}"; then
        ok "fast Gradle checks passed"
    else
        explain_gradle_failure
        fail "fast Gradle checks failed"
    fi
else
    skip "scope $scope: no Gradle-relevant paths changed against ${PRECHECK_BASE:-origin/dev}, skipping Gradle"
fi

if [ "$scope" = other ] || [ "$mode" = full ]; then
    step "Shell syntax of changed scripts"
    bad=0
    while IFS= read -r path; do
        case "$path" in *.sh) ;; *) continue ;; esac
        [ -f "$path" ] || continue
        bash -n "$path" || { echo "syntax error: $path" >&2; bad=1; }
    done <<< "$changed"
    [ "$bad" -eq 0 ] || fail "fix the shell syntax errors above"
    ok "changed shell scripts parse"
fi

step "Scan for stray TODO/FIXME without issue links"
# Kotlin only, whole words: `XXX` is also a date-format offset pattern in resource files.
if grep -rEnw --include='*.kt' --include='*.kts' '(TODO|FIXME|XXX)' src/ 2>/dev/null | grep -v '#[0-9]'; then
    fail "found TODO/FIXME without an issue link (#NNN). Add link or remove."
fi
ok "no unlinked TODO/FIXME"

step "Scan for AI references in tracked files"
if git grep -nE '(Co-Authored-By:.*Claude|Co-Authored-By:.*GPT|Generated with.*Claude|🤖)' -- ':!scripts/precheck.sh' ':!scripts/agent-workflow.sh' ':!.claude/' \
    ':!scripts/check-commit-authors.sh' ':!tests/scripts/check-commit-authors-test.sh' 2>/dev/null; then
    fail "found AI references in tracked files — strip them before pushing"
fi
ok "no AI references in tracked files"

step "Scan for emojis in source code"
# PCRE (-P) understands \x{...}; -I skips binaries. Exit 1 means no match; 2+ means the scan
# itself failed, which must not read as clean.
emoji_status=0
emoji_scan="$(LC_ALL=C.UTF-8 git grep -lIP '[\x{1F300}-\x{1FAFF}]' -- 'src/**' '*.kt' '*.kts' '*.md' ':!.claude/' ':!scripts/precheck.sh' 2>&1)" ||
    emoji_status=$?
if [ "$emoji_status" -ge 2 ]; then
    fail "emoji scan failed: $emoji_scan"
elif [ "$emoji_status" -eq 0 ]; then
    printf '%s\n' "$emoji_scan"
    fail "found emojis in source/docs — remove them"
fi
ok "no emojis in source"

printf '\n\033[1;32mAll precheck steps passed\033[0m (%s mode, scope %s, %ss). CI runs the full suite. Ready to push.\n' \
    "$mode" "$scope" "$((SECONDS - started))"
