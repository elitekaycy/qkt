# Test coverage

Every line of production code runs under a test. Two gates hold that line, both in
`check.yml` on every pull request into `dev`:

| Gate | Tool | What fails it |
|---|---|---|
| Changed lines | `scripts/check-diff-coverage.sh` ([diff-cover](https://github.com/Bachmann1234/diff_cover) over Kover's XML) | Any line the PR adds or edits under `src/main` that no test executes. |
| Whole codebase | `koverVerify` (Kover), part of `./gradlew check` | Total line or branch coverage below the floor in `config/coverage-floor.properties`. |

The floor only goes up. When a PR raises total coverage, raise the floor in the same PR to the
new whole-number value (the percentage printed by `./gradlew koverLog`); never lower it. The
end state is `line=100` and `branch=100`.

## Running it locally

```bash
./gradlew test koverXmlReport koverHtmlReport   # report: build/reports/kover/html/index.html
./gradlew koverLog                              # total line coverage, printed
./gradlew koverVerify                           # the floor
pip install -r config/coverage-requirements.txt
scripts/check-diff-coverage.sh origin/dev       # your branch's changed lines
```

`scripts/check-diff-coverage.sh` prints each uncovered changed line by file and line number.
`DIFF_COVERAGE_HTML=out.html` writes a browsable report.

## What counts

- Kover measures the unit-test run (`./gradlew test`, which excludes the `e2e`, `stress` and
  `soak` tags). Code reached only by an e2e test is uncovered: give it a unit test.
- Nothing is uploaded anywhere. Reports stay in the CI run's `coverage` artifact.
- The changed-lines gate is line-level. A changed `if` whose one branch never runs passes it but
  counts against the branch floor, so cover both arms.

## Exclusions

`@ExcludeFromCoverage("reason")` (`com.qkt.common.coverage`) removes a declaration from the
report. It is for code that only a running process can reach: a `main` that wires real stdin,
sockets and signal handlers, or a shutdown hook. It is not for code that is merely awkward to
test; split the pure part out and test that. Every use names its reason, and a reviewer can
refuse it.

The hand-run harnesses in `qkt.dev-tasks` (`runDemo`, `runLiveDemo`, `runMaxAudit`, `runParity*`,
`runStateDemo`) are excluded by class name in `build-logic/src/main/kotlin/qkt.coverage.gradle.kts`:
they are mains against live feeds that no product code calls. Adding to that list needs the same
justification.

## Writing the test

Follow [the testing rules](conventions.md): JUnit 5, AssertJ, real project types over mocks,
names that read as behaviour. A test that only executes a line without asserting what it does
is not coverage; reviewers reject it.
