#!/usr/bin/env bash
# A change whose new src/main lines all ran under test passes the diff-coverage gate; a change
# with an uncovered new line fails it. Uses a hand-written JaCoCo report, the format Kover writes.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp" && git init -q -b dev && git config user.name 'Dickson Anyaele' && git config user.email dev@example.com
mkdir -p src/main/kotlin/com/qkt/demo
printf 'package com.qkt.demo\n\nfun base() = 1\n' > src/main/kotlin/com/qkt/demo/Demo.kt
git add . && git commit -q -m 'chore(build): base'
git checkout -q -b feature
printf 'package com.qkt.demo\n\nfun base() = 1\n\nfun added() = 2\n\nfun other() = 3\n' > src/main/kotlin/com/qkt/demo/Demo.kt
git commit -qam 'feat(app): add two functions'

report() {  # status of line 7: ci for line 5 is always 1
    cat > report.xml <<XML
<?xml version="1.0" encoding="UTF-8"?>
<report name="t"><package name="com/qkt/demo"><sourcefile name="Demo.kt">
<line nr="3" mi="0" ci="1" mb="0" cb="0"/><line nr="5" mi="0" ci="1" mb="0" cb="0"/>
<line nr="7" mi="$1" ci="$2" mb="0" cb="0"/>
</sourcefile></package></report>
XML
}
check() { bash "$repo_root/scripts/check-diff-coverage.sh" dev report.xml > out.txt 2>&1; }

report 0 1
check || { cat out.txt; echo "FAIL a fully covered change was refused"; exit 1; }
echo "ok a fully covered change passes"

report 1 0
if check; then cat out.txt; echo "FAIL an uncovered new line was accepted"; exit 1; fi
grep -q 'Demo.kt' out.txt || { cat out.txt; echo "FAIL the refusal does not name the file"; exit 1; }
echo "ok an uncovered new line is refused and named"

rm report.xml
if check; then echo "FAIL a missing report was accepted"; exit 1; fi
echo "ok a missing report is refused"
