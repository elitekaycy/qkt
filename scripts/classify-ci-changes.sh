#!/usr/bin/env bash
# Classifies a pull request's changed paths for check.yml: prints `docs-only` when every path is documentation
# that no build step or test reads, otherwise `full`. Paths come one per line on stdin. An empty list, or any
# path this script does not recognise as documentation, is `full`: the rule only ever skips work that is known
# not to matter. See docs/contributing/test-coverage.md ("Docs-only pull requests").
set -euo pipefail

# Documentation pages that tests or the build read. A test that starts reading another docs path must be added
# here in the same PR (tests/scripts/classify-ci-changes-test.sh checks the known readers against this list).
read_by_tests() {
    case "$1" in
        docs/reference/dsl/*|docs/reference/dsl-grammar.md) return 0 ;;     # DslReferenceBlocks, qkt.testing inputs
        docs/reference/cli-commands.md|docs/reference/bot-cli.md) return 0 ;; # CliDocsSyncTest
        docs/examples/*|docs/tutorials/*|docs/how-to/*) return 0 ;;          # ExampleStrategyBlocks, DocsShownOutputTest
        docs/index.md) return 0 ;;                                           # DocsShownOutputTest
        docs/assets/*) return 0 ;;                                           # build.gradle.kts (Dokka logo)
    esac
    return 1
}

is_docs() {
    local path="$1"
    case "$path" in
        src/*|build-logic/*|gradle/*|config/*|scripts/*|tests/*|verification/*|attestation/*) return 1 ;;
        .github/*|examples/*|strategies/*|editor/*|*.gradle.kts|gradlew*) return 1 ;;
    esac
    read_by_tests "$path" && return 1
    case "$path" in
        docs/*|*.md|mkdocs.yml|requirements-docs.txt) return 0 ;;
    esac
    return 1
}

seen=0
while IFS= read -r path || [ -n "$path" ]; do
    [ -z "$path" ] && continue
    seen=$((seen + 1))
    if ! is_docs "$path"; then
        echo "full"
        echo "runs the full build: $path is not documentation-only" >&2
        exit 0
    fi
done

if [ "$seen" -eq 0 ]; then
    echo "full"
    echo "runs the full build: no changed paths" >&2
    exit 0
fi
echo "docs-only"
echo "docs-only: all $seen changed path(s) are documentation no test reads" >&2
