# Phase 11a — Open-Source Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the open-source posture gap on the qkt repo: Apache 2.0 LICENSE, rewritten README, CONTRIBUTING update, CODE_OF_CONDUCT, SECURITY, GitHub Actions CI, issue + PR templates, release-process doc, plus 22 backfill tags + GitHub Releases for every shipped phase.

**Architecture:** Pure additive documentation + repo plumbing. No source code changes. The two copyrighted texts (Apache 2.0 license, Contributor Covenant 2.1) are fetched via `curl` from their canonical sources rather than inlined — guarantees current text, sidesteps reproduction.

**Tech Stack:** Markdown + YAML, GitHub Actions, `gh` CLI for release authoring, `git tag` for backfill.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`

**Branch:** `phase11a-oss-baseline` (already created and active).

---

## File Structure

### New files

```
qkt/
├── LICENSE                             # Apache 2.0 (curl-fetched)
├── CODE_OF_CONDUCT.md                  # Contributor Covenant 2.1 (curl-fetched)
├── SECURITY.md                         # written
├── .github/
│   ├── workflows/
│   │   └── check.yml                   # written
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.md               # written
│   │   └── feature_request.md          # written
│   └── PULL_REQUEST_TEMPLATE.md        # written
└── docs/
    ├── release-process.md              # written
    └── phases/
        └── phase-11a-oss-baseline.md   # written (changelog)
```

### Modified files

- `README.md` — full rewrite.
- `CONTRIBUTING.md` — add release-flow section, refresh phase examples.

### Out-of-tree work (post-merge)

- 22 annotated git tags backfilled.
- 22 GitHub Releases authored via `gh release create`.

---

## Tasks

### Task 1: Add LICENSE (Apache 2.0)

**Files:**
- Create: `LICENSE`

The LICENSE file is the canonical Apache 2.0 text fetched from apache.org, prepended with the copyright notice for this project. Fetching guarantees the official current text — never paraphrase a license.

- [ ] **Step 1: Fetch the canonical Apache 2.0 text**

```bash
cd /home/dickson/Desktop/personal/qkt
curl -fsSL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE
wc -l LICENSE
```

Expected: file written, ~202 lines.

- [ ] **Step 2: Prepend the project copyright notice**

The Apache file as fetched is the bare license text. Prepend a 2-line project copyright header so anyone reading `LICENSE` knows whose copyright is governed by it. Use a temporary file to avoid partial-write corruption.

```bash
cd /home/dickson/Desktop/personal/qkt
{
  echo "Copyright 2026 Dickson Anyaele"
  echo ""
  cat LICENSE
} > LICENSE.tmp && mv LICENSE.tmp LICENSE
head -3 LICENSE
```

Expected: first three lines are `Copyright 2026 Dickson Anyaele`, blank, then `                                 Apache License`.

- [ ] **Step 3: Verify**

```bash
wc -l /home/dickson/Desktop/personal/qkt/LICENSE
grep -c "Apache License" /home/dickson/Desktop/personal/qkt/LICENSE
grep -c "Copyright 2026 Dickson Anyaele" /home/dickson/Desktop/personal/qkt/LICENSE
```

Expected: ~204 lines, ≥1 match for each grep.

- [ ] **Step 4: Commit**

```bash
git add LICENSE
git commit -m "docs: add Apache 2.0 LICENSE"
```

---

### Task 2: Add SECURITY.md

**Files:**
- Create: `SECURITY.md`

- [ ] **Step 1: Create the file**

Write `/home/dickson/Desktop/personal/qkt/SECURITY.md` with this content:

```markdown
# Security Policy

## Reporting a vulnerability

Email dicksonanyaele1234@gmail.com with "qkt security" in the subject.

We aim to acknowledge within 7 days and provide a fix or mitigation timeline within 14 days. Please do not file public GitHub issues for security reports.

## Scope

In scope:
- Code execution, deserialization, or path traversal in the engine, broker, or data store layers
- Credential leakage via logs, exception messages, or persisted state
- Order-routing logic that can produce unintended live-broker actions

Out of scope:
- Strategy bugs (incorrect P&L is a strategy concern, not an engine vulnerability)
- DoS via crafted market data — we accept that adversarial feeds can OOM us
- Issues in transitive dependencies that do not affect qkt's actual behavior

## Supported versions

Pre-1.0 releases: only the latest minor receives security fixes. Users on older minors should upgrade to the latest. Once 1.0 ships, this policy will be revisited.
```

- [ ] **Step 2: Commit**

```bash
git add SECURITY.md
git commit -m "docs: add security disclosure policy"
```

---

### Task 3: Add CODE_OF_CONDUCT.md (Contributor Covenant 2.1)

**Files:**
- Create: `CODE_OF_CONDUCT.md`

- [ ] **Step 1: Fetch Contributor Covenant 2.1**

```bash
cd /home/dickson/Desktop/personal/qkt
curl -fsSL https://www.contributor-covenant.org/version/2/1/code_of_conduct.md \
  -o CODE_OF_CONDUCT.md
wc -l CODE_OF_CONDUCT.md
```

Expected: file written, ~130 lines, contains the heading "Contributor Covenant Code of Conduct."

- [ ] **Step 2: Replace the contact placeholder**

The template ships with a literal `[INSERT CONTACT METHOD]` token where the maintainer email goes. Replace it.

```bash
cd /home/dickson/Desktop/personal/qkt
sed -i 's|\[INSERT CONTACT METHOD\]|dicksonanyaele1234@gmail.com|g' CODE_OF_CONDUCT.md
grep -c "dicksonanyaele1234@gmail.com" CODE_OF_CONDUCT.md
grep -c "INSERT CONTACT METHOD" CODE_OF_CONDUCT.md
```

Expected: ≥1 match for the email, 0 matches for the placeholder.

- [ ] **Step 3: Verify**

```bash
head -1 /home/dickson/Desktop/personal/qkt/CODE_OF_CONDUCT.md
grep -c "Contributor Covenant" /home/dickson/Desktop/personal/qkt/CODE_OF_CONDUCT.md
```

Expected: heading line begins with `# Contributor Covenant`, ≥1 grep match.

- [ ] **Step 4: Commit**

```bash
git add CODE_OF_CONDUCT.md
git commit -m "docs: add Contributor Covenant 2.1 code of conduct"
```

---

### Task 4: Add GitHub Actions CI workflow

**Files:**
- Create: `.github/workflows/check.yml`

- [ ] **Step 1: Create the directory**

```bash
mkdir -p /home/dickson/Desktop/personal/qkt/.github/workflows
```

- [ ] **Step 2: Write `.github/workflows/check.yml`**

Write `/home/dickson/Desktop/personal/qkt/.github/workflows/check.yml` with this content:

```yaml
name: check

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: gradle/actions/setup-gradle@v3
      - run: ./gradlew check --no-daemon
```

- [ ] **Step 3: Verify YAML is well-formed**

```bash
python3 -c "import yaml; yaml.safe_load(open('/home/dickson/Desktop/personal/qkt/.github/workflows/check.yml'))" && echo "yaml OK"
```

Expected: `yaml OK` printed (no exception).

If `python3` or `yaml` aren't available, the workflow will validate when GitHub picks it up after push — acceptable.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/check.yml
git commit -m "ci: add GitHub Actions check workflow"
```

---

### Task 5: Add issue templates

**Files:**
- Create: `.github/ISSUE_TEMPLATE/bug_report.md`
- Create: `.github/ISSUE_TEMPLATE/feature_request.md`

- [ ] **Step 1: Create the directory**

```bash
mkdir -p /home/dickson/Desktop/personal/qkt/.github/ISSUE_TEMPLATE
```

- [ ] **Step 2: Write `bug_report.md`**

Write `/home/dickson/Desktop/personal/qkt/.github/ISSUE_TEMPLATE/bug_report.md` with this content:

```markdown
---
name: Bug report
about: Report a bug in qkt
labels: bug
---

## Description

What went wrong? One or two sentences.

## Reproduction

Steps to reproduce:

1. ...
2. ...
3. ...

Minimal code snippet (Kotlin) if applicable.

## Expected vs actual

- Expected: ...
- Actual: ...

## Environment

- qkt version: `v0.X.Y` (or commit SHA)
- JDK version: `java -version` output
- OS: ...

## Logs / stack trace

```
paste relevant log lines or stack trace
```
```

- [ ] **Step 3: Write `feature_request.md`**

Write `/home/dickson/Desktop/personal/qkt/.github/ISSUE_TEMPLATE/feature_request.md` with this content:

```markdown
---
name: Feature request
about: Suggest a feature or enhancement
labels: enhancement
---

## Use case

What problem are you trying to solve? What workflow does qkt not support today?

## Proposed API or behavior

How would the feature work? Sketch the API surface or behavior.

## Alternatives considered

Other approaches you considered and why this one fits better.

## Phase fit

Which phase / area of the engine does this touch? (See `docs/phases/`.)
```

- [ ] **Step 4: Commit**

```bash
git add .github/ISSUE_TEMPLATE/bug_report.md .github/ISSUE_TEMPLATE/feature_request.md
git commit -m "docs: add issue templates for bug reports and feature requests"
```

---

### Task 6: Add PR template

**Files:**
- Create: `.github/PULL_REQUEST_TEMPLATE.md`

- [ ] **Step 1: Write the template**

Write `/home/dickson/Desktop/personal/qkt/.github/PULL_REQUEST_TEMPLATE.md` with this content:

```markdown
## Phase

Phase <N>. Spec: `docs/superpowers/specs/...`. Plan: `docs/superpowers/plans/...`.

## Summary

<1–3 sentences on the why.>

## Changes

- <bullet per meaningful change>

## Tests

- <new tests added>
- <existing tests updated>
- Local: `./gradlew build` green, <N> tests passing.

## Backwards compatibility

<"Phase X-only — no compat concern" OR specific notes for breaking changes.>

## Out of scope

- <items deferred, with the phase or backlog where they are tracked>

## Risk

<Low / Medium / High and why.>
```

- [ ] **Step 2: Commit**

```bash
git add .github/PULL_REQUEST_TEMPLATE.md
git commit -m "docs: add pull request template"
```

---

### Task 7: Add docs/release-process.md

**Files:**
- Create: `docs/release-process.md`

- [ ] **Step 1: Write the file**

Write `/home/dickson/Desktop/personal/qkt/docs/release-process.md` with this content:

````markdown
# Release process

## Versioning

SemVer 0.x.y while pre-1.0:

- **minor (`0.X.0`)** — phase number. Phase 10 → `0.10.x`. Phase 11 → `0.11.x`.
- **patch (`0.X.Y`)** — sub-phase letters (a/b/c/d-a/d-b/...) in chronological order, or hotfixes between phases. Phase 10 = `0.10.0`, Phase 10b = `0.10.1`, a hypothetical Phase 10c hotfix = `0.10.2`.

Breaking changes are acceptable in minor bumps until 1.0. This matches the "no backwards compatibility cruft" stance documented in the qkt skill.

Once a stable public DSL ships and the API is documented as stable, we bump to `1.0.0` and standard SemVer rules apply (no breaking changes in minor).

## Tagging

After a `merge: phase X ...` commit lands on main:

```bash
git checkout main && git pull
git tag -a v0.X.Y -m "phase X — <short description>"
git push origin v0.X.Y
```

Tags are annotated and immutable. **Never** force-update or delete a published tag. If you push a wrong tag, immediately push a corrected next-patch tag and note the mistake in the release notes.

## GitHub Release

```bash
gh release create v0.X.Y \
  --title "v0.X.Y — phase X <description>" \
  --notes-file docs/phases/phase-<N>-<topic>.md
```

If no phase changelog exists (phases 1-6 predate the convention), use `--notes "<merge commit subject>"` instead.

Mark as "Latest release" if it is.

## Hotfix policy

Pre-1.0 hotfixes:
1. Fix on a `fix-<short-description>` branch off main.
2. Merge via `--no-ff` with `merge: fix <description>`.
3. Tag as the next patch on the current minor: e.g. if main is at `v0.10.1`, the hotfix is `v0.10.2`.
4. No back-port branches. Users on older minors update to latest.

## Mapping phase merges to versions

| Phase merge | Version |
|---|---|
| phase 1 trading engine | v0.1.0 |
| phase 2a event bus | v0.2.0 |
| phase 2b candle aggregator | v0.2.1 |
| phase 3 risk and positions | v0.3.0 |
| phase 3b pnl and bigdecimal | v0.3.1 |
| phase 4 backtest harness | v0.4.0 |
| phase 5 indicators | v0.5.0 |
| phase 6 data store | v0.6.0 |
| phase 6 cleanup | v0.6.1 |
| phase 7a live runtime refactor | v0.7.0 |
| phase 7b live runtime + warmup | v0.7.1 |
| phase 7c TradingView vendor | v0.7.2 |
| phase 7d-a broker abstraction | v0.7.3 |
| phase 7d-b OrderManager | v0.7.4 |
| phase 7e Bybit Spot + composite | v0.7.5 |
| phase 7f broker resilience | v0.7.6 |
| phase 7g reconciliation + balances | v0.7.7 |
| phase 7h derivatives + rate limit | v0.7.8 |
| phase 8 strategy context + pnl attribution | v0.8.0 |
| phase 9 risk engine | v0.9.0 |
| phase 10 backtest reporting | v0.10.0 |
| phase 10b parameter sweep | v0.10.1 |

Intermediate maintenance merges (TradingView fixes, onTick/onCandle refactor) are not tagged — they are not user-facing milestones.
````

- [ ] **Step 2: Commit**

```bash
git add docs/release-process.md
git commit -m "docs: add release process and versioning policy"
```

---

### Task 8: Update CONTRIBUTING.md

**Files:**
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Read the current file to confirm anchor strings**

```bash
grep -n "## Pull requests\|## Code style" /home/dickson/Desktop/personal/qkt/CONTRIBUTING.md
```

Expected: `## Pull requests` heading exists; `## Code style` heading exists right after.

- [ ] **Step 2: Insert the Releases section between "Pull requests" and "Code style"**

Use `Edit` to replace the boundary. The current content has:

```
Merge with `--no-ff` and the merge commit message `merge: phase <N> <short-description>`. Delete the feature branch after merge.

## Code style
```

Replace that block with:

```
Merge with `--no-ff` and the merge commit message `merge: phase <N> <short-description>`. Delete the feature branch after merge.

## Releases

After a `merge: phase X ...` commit lands on main, the maintainer:

1. Tags the merge commit: `git tag -a v0.X.Y -m "phase X — <description>"`.
2. Pushes the tag: `git push origin v0.X.Y`.
3. Creates a GitHub Release using the tag, with the corresponding `docs/phases/phase-<N>-<topic>.md` content as the release body.

See `docs/release-process.md` for the full process and version-number conventions.

## Code style
```

- [ ] **Step 3: Verify the section landed**

```bash
grep -n "^## " /home/dickson/Desktop/personal/qkt/CONTRIBUTING.md
```

Expected: shows `## Releases` between `## Pull requests` and `## Code style`.

- [ ] **Step 4: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs: document release flow in CONTRIBUTING"
```

---

### Task 9: Rewrite README.md

**Files:**
- Modify: `README.md` (full replace)

- [ ] **Step 1: Replace the file**

Write `/home/dickson/Desktop/personal/qkt/README.md` with this content:

````markdown
# qkt

[![check](https://github.com/elitekaycy/qkt/actions/workflows/check.yml/badge.svg)](https://github.com/elitekaycy/qkt/actions/workflows/check.yml)

An event-driven trading engine in Kotlin with backtest replay, parameter sweeps, attribution-aware risk, and a future SQL-like DSL.

## Status

Pre-1.0. Latest release: [`v0.10.1`](https://github.com/elitekaycy/qkt/releases/latest). Breaking changes happen in minor releases until `1.0.0`. The engine is functional and tested but the public API is not yet declared stable.

See [`docs/phases/`](docs/phases/) for per-phase changelogs and [`docs/release-process.md`](docs/release-process.md) for versioning.

## Features

- **Tick + candle pipeline** with a deterministic event bus ([phase 2a](docs/phases/), [phase 2b](docs/phases/)).
- **Multi-strategy support** with per-strategy P&L attribution ([phase 8](docs/phases/phase-8-strategy-context-and-pnl-attribution.md)).
- **Risk engine** — equity tracking, drawdown halts, daily loss halts, halt-as-state with operator-driven resume ([phase 9](docs/phases/phase-9-risk-engine.md)).
- **Bybit Spot + Linear (USDT)** live trading with reconciliation, rate limiting, and connection resilience ([phase 7e–7h](docs/phases/)).
- **Backtest replay engine** with full reporting: equity curves, Sharpe, Calmar, profit factor, win/loss stats ([phase 10](docs/phases/phase-10-backtest-reporting.md)).
- **Parameter sweep harness** with sequential or fixed-pool parallel execution and ranked summary reports ([phase 10b](docs/phases/phase-10b-parameter-sweep.md)).
- **TradingView live vendor** (anonymous, free-tier) for paper trading ([phase 7c](docs/phases/)).
- **On-disk content-addressable data store** with Dukascopy auto-fetch and bring-your-own CSV ([phase 6](docs/phases/)).

## Quickstart

Requires JDK 21 (Gradle's toolchain auto-provisions if not local).

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
./gradlew build      # compile + test + assemble
./gradlew run        # main application — prints fills, exits
```

A minimal in-process backtest:

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick

val ticks = (1..100).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }
val result = Backtest(
    strategies = listOf("buy-and-hold" to MyStrategy()),
    ticks = ticks,
    candleWindow = TimeWindow.ONE_MINUTE,
).run()

println("Total P&L: ${result.global.totalPnL}")
println("Sharpe: ${result.global.sharpeRatio}")
println("Max drawdown: ${result.global.maxDrawdown}")
```

For real historical data via the Dukascopy fetcher or live trading via TradingView, see the relevant phase changelogs in [`docs/phases/`](docs/phases/).

## Architecture

```
Tick → Engine → Strategy → Signal → Order → Broker → Trade
```

Single-threaded event-driven pipeline. Every component is deterministic given its inputs and seeds. `Clock`, `IdGenerator`, and `SequenceGenerator` are interfaces so backtest is a component swap, not a rewrite. State that is shared between producers and consumers is exposed via read-only interfaces — the type system enforces the read/write split.

For depth, read the per-phase design specs in [`docs/superpowers/specs/`](docs/superpowers/specs/).

## Repository layout

```
src/main/kotlin/com/qkt/
├── app/             entry points: Main, LiveSession, TradingPipeline, IndicatorWarmer
├── backtest/        Backtest, BacktestResult, EquitySample, EquityCurveCollector,
│                    PerformanceReport, ReportBuilder, SampleCadence, TradeRecord
│   ├── metrics/     profitFactor, winLossStats, sharpe, calmar
│   ├── report/      BacktestReportWriter, SweepReportWriter, ReportSerializer
│   └── sweep/       BacktestSweep, SweepRun, SweepResult
├── broker/          Broker, PaperBroker, BybitBroker, CompositeBroker
├── bus/             EventBus
├── candles/         CandleAggregator, TimeWindow
├── common/          Clock, FixedClock, SystemClock, Money, Side, IdGenerator,
│                    TradingCalendar (crypto, fx, NYSE), TimeRange, SessionAnchor
├── engine/          Engine
├── events/          Event, TickEvent, CandleEvent, SignalEvent, OrderEvent,
│                    BrokerEvent (Filled, Rejected, Reconciled, ...), RiskEvent
├── execution/       Order, OrderRequest, Trade, OrderType
├── marketdata/      Tick, Candle, MarketSource, MarketPriceTracker, TickFeed
│                    (Historical, Sequence, Merging), data store, vendors
├── pnl/             PnLCalculator, StrategyPnL, PnLProvider, StrategyPnLView
├── positions/       PositionTracker, StrategyPositionTracker, Position
├── risk/            RiskEngine, RiskState, EquityTracker, DrawdownTracker,
│                    DailyPnLTracker, RiskRule, HaltRule, RiskView, rules/
└── strategy/        Strategy, StrategyContext, Signal, Mode, WarmupSpec, samples/
```

## Build, run, test

```bash
./gradlew build              # compile + test + ktlint + assemble
./gradlew test               # tests only
./gradlew run                # main application
./gradlew runLiveDemo        # TradingView paper-trading demo
./gradlew test -PincludeTags=e2e  # run live smoke tests (manual)
./gradlew ktlintFormat       # auto-format
```

Pre-push helper:

```bash
./scripts/precheck.sh
```

After cloning, install the git hook so precheck runs automatically before every push:

```bash
./scripts/install-hooks.sh
```

## Getting real data

Phase 6 ships a content-addressable on-disk data store at `~/.qkt/data/` (override via the `QKT_DATA_HOME` environment variable). Strategies query this store via `Backtest.fromStore(...)` or directly via `DataStore.openFeed(...)`. The store is empty on first install — populate it via Dukascopy auto-fetch, bring-your-own CSV, or the bundled sample fixture. See the [phase 6 changelog](docs/phases/) for the full guide.

## Live trading

Phase 7 ships a live runtime alongside the historical backtest. Vendors include TradingView (anonymous, free-tier) for market data and Bybit (Spot + Linear/USDT) for execution. Live ticks feed the same `TradingPipeline` your backtest uses; the only difference is the `TickFeed` (live) and the `Clock` (system time). See [`docs/phases/phase-7-live-runtime.md`](docs/phases/phase-7-live-runtime.md) and the Bybit phase changelogs for setup and constraints.

## Documentation

- [`docs/phases/`](docs/phases/) — per-phase changelogs (the authoritative "what's in qkt today" reference).
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — design specs per phase.
- [`docs/superpowers/plans/`](docs/superpowers/plans/) — implementation plans per phase.
- [`docs/release-process.md`](docs/release-process.md) — versioning and tagging policy.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to contribute.
- [`SECURITY.md`](SECURITY.md) — vulnerability disclosure.
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — community standards.

## License

Apache 2.0 — see [LICENSE](LICENSE).
````

- [ ] **Step 2: Verify the file is well-formed**

```bash
wc -l /home/dickson/Desktop/personal/qkt/README.md
grep -c "^## " /home/dickson/Desktop/personal/qkt/README.md
```

Expected: ~150-200 lines, ≥10 second-level headings.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: rewrite README for Phase 10b state"
```

---

### Task 10: Add phase changelog

**Files:**
- Create: `docs/phases/phase-11a-oss-baseline.md`

- [ ] **Step 1: Write the changelog**

Write `/home/dickson/Desktop/personal/qkt/docs/phases/phase-11a-oss-baseline.md` with this content:

````markdown
# Phase 11a — Open-Source Baseline

## Summary

Phase 11a closes the open-source posture gap on the qkt repo. After 22 phases of engine work, the repo had no LICENSE, no CI, a stale README from Phase 1, no Code of Conduct, no security policy, no release/versioning policy, and no tags. This phase ships all of those plus 22 backfill tags + GitHub Releases for every shipped phase.

This is meta-work, not engine work. No production source code changes. The deliverable is documentation, repo plumbing, CI, and a public release history.

## What's new

- `LICENSE` — Apache 2.0, copyright 2026 Dickson Anyaele.
- `README.md` — full rewrite. Reflects Phase 10b state (was stuck at Phase 1). Adds CI badge, feature list linking to phase changelogs, current source tree, links to all docs.
- `CODE_OF_CONDUCT.md` — Contributor Covenant 2.1 with maintainer email.
- `SECURITY.md` — vulnerability disclosure email + 7-day acknowledgment SLA + scope statement.
- `.github/workflows/check.yml` — GitHub Actions CI: `./gradlew check` on push to main and on PR.
- `.github/ISSUE_TEMPLATE/bug_report.md` — bug report template.
- `.github/ISSUE_TEMPLATE/feature_request.md` — feature request template.
- `.github/PULL_REQUEST_TEMPLATE.md` — PR template mirroring the qkt skill PR description format.
- `docs/release-process.md` — SemVer 0.x.y policy, tagging procedure, GitHub Release authoring, phase → version mapping table.
- `CONTRIBUTING.md` — adds a Releases section pointing to `docs/release-process.md`.
- 22 backfill tags pushed (`v0.1.0` through `v0.10.1`) covering every named phase merge.
- 22 GitHub Releases authored from phase changelogs (phases 7+) or merge commit subjects (phases 1-6).

## Migration from previous phase

None. Phase 11a is purely additive documentation + repo plumbing. No source code changes.

## Usage cookbook

### Cutting a release for a future phase

After a `merge: phase X ...` commit lands on main:

```bash
git checkout main && git pull
git tag -a v0.X.Y -m "phase X — <short description>"
git push origin v0.X.Y
gh release create v0.X.Y \
  --title "v0.X.Y — phase X <description>" \
  --notes-file docs/phases/phase-<N>-<topic>.md
```

Mark as "Latest release" on the GitHub UI if it is.

### Filing a bug report

Open <https://github.com/elitekaycy/qkt/issues/new?template=bug_report.md>. The template prompts for description, repro, expected vs actual, environment, and logs.

### Reporting a security issue

Email dicksonanyaele1234@gmail.com with "qkt security" in the subject. Do not open a public issue. Acknowledgment within 7 days; mitigation timeline within 14.

### Pinning to a specific phase

Consumers of qkt can pin to a tagged release in their own clone:

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
git checkout v0.10.0   # phase 10 backtest reporting only, no parameter sweep
./gradlew assemble
```

## Testing patterns

Phase 11a has no production tests. The CI workflow itself is the verification — `./gradlew check` running green on every push and PR is the durable signal that the engine is healthy. The pre-push precheck (`./scripts/precheck.sh`) runs the same gate locally.

## Known limitations

- **No publishing to Maven Central / JitPack / GitHub Packages.** Consumers clone + build. Publishing is a separate decision tree (group ID, signing, Sonatype account) and not justified until someone asks.
- **No release automation.** Tags pushed manually, releases authored manually via `gh release create`. A `release.yml` workflow is a future polish.
- **No docs site / GitHub Pages.** `docs/` in the repo is enough.
- **No top-level CHANGELOG.md aggregator.** `docs/phases/` is the authoritative timeline.
- **No retroactive changelog writing for phases 1-6.** Their tags use the merge commit subject as the release body.
- **No CLA, dual-licensing, or sponsorship setup.** Apache 2.0 is enough for v1.
- **No multi-OS / multi-JDK CI matrix.** Single Ubuntu + JDK 21 runner.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11a.md`
- Merge commit: filled in at merge time.
````

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-11a-oss-baseline.md
git commit -m "docs: phase 11a oss baseline changelog"
```

---

### Task 11: Final build + precheck

**Files:** none changed.

- [ ] **Step 1: Run full build**

```bash
cd /home/dickson/Desktop/personal/qkt
./gradlew build
```

Expected: BUILD SUCCESSFUL. (No code changes since main, so this should be a fast cache hit.)

- [ ] **Step 2: Run precheck**

```bash
bash /home/dickson/Desktop/personal/qkt/scripts/precheck.sh
```

Expected: all green.

- [ ] **Step 3: Read commit log**

```bash
git -C /home/dickson/Desktop/personal/qkt log --oneline main..HEAD
```

Expected: 11 commits (spec + plan + LICENSE + SECURITY + CoC + CI + 2 issue templates + PR template + release-process + CONTRIBUTING update + README rewrite + changelog), each with a clean conventional-commit subject. No emoji, no AI footer.

If any commit subject is wrong, amend (only the most recent — never published commits). For older commits with bad subjects, leave as-is and note in the merge commit.

- [ ] **Step 4: No commit (this task is verification only)**

---

### Task 12: Hand off to finishing-a-development-branch

**Files:** none changed.

- [ ] **Step 1: Announce the handoff**

Tell the user: "I'm using the finishing-a-development-branch skill to complete this work."

- [ ] **Step 2: Invoke the skill**

Invoke `superpowers:finishing-a-development-branch`. Standard 4 options (merge locally, PR, keep, discard). qkt convention is `git merge --no-ff` with `merge: phase 11a oss baseline` on the merge commit.

- [ ] **Step 3: Push to origin**

After the merge, push:

```bash
git push origin main
```

The pre-push hook runs precheck (build + tests + clean tree + scans). All should pass since no code changed.

---

### Task 13: Backfill 22 tags

**Files:** none in working tree (tag refs only).

This task runs **after** the Phase 11a branch is merged. Tags point to existing merge commits already on main; we don't need the Phase 11a content for this.

- [ ] **Step 1: Confirm merge commit SHAs**

```bash
cd /home/dickson/Desktop/personal/qkt
git log --merges --oneline | head -30
```

Expected: list shows the 22 named phase merge commits + 3 maintenance merges. SHAs should match the table below.

If any SHA listed below does not match what the local repo shows, **stop and ask the user.** History may have changed since the spec was written.

| Tag | Merge SHA | Subject |
|---|---|---|
| `v0.1.0` | `d0bf3b8` | phase 1 trading engine |
| `v0.2.0` | `6da8cab` | phase 2a event bus |
| `v0.2.1` | `7bc92dc` | phase 2b candle aggregator |
| `v0.3.0` | `6e2381f` | phase 3 risk and positions |
| `v0.3.1` | `0cb05a4` | phase 3b pnl and bigdecimal |
| `v0.4.0` | `54e6b4c` | phase 4 backtest harness |
| `v0.5.0` | `bf25efc` | phase 5 indicators |
| `v0.6.0` | `a3132fe` | phase 6 data store |
| `v0.6.1` | `e826a12` | phase 6 cleanup |
| `v0.7.0` | `55f4561` | phase 7a live runtime refactor |
| `v0.7.1` | `44d997f` | phase 7b live runtime + warmup |
| `v0.7.2` | `365a6cd` | phase 7c TradingView vendor |
| `v0.7.3` | `a3f91b6` | phase 7d-a broker abstraction |
| `v0.7.4` | `cdfba6a` | phase 7d-b OrderManager |
| `v0.7.5` | `81f3a6e` | phase 7e Bybit Spot + composite |
| `v0.7.6` | `5fb6ce0` | phase 7f broker resilience |
| `v0.7.7` | `6eb6ec4` | phase 7g reconciliation + balances |
| `v0.7.8` | `7d15f71` | phase 7h derivatives + rate limit |
| `v0.8.0` | `850c14a` | phase 8 strategy context + pnl attribution |
| `v0.9.0` | `9f447f8` | phase 9 risk engine |
| `v0.10.0` | `634b2e3` | phase 10 backtest reporting |
| `v0.10.1` | `719620b` | phase 10b parameter sweep |

- [ ] **Step 2: Push the tags in chronological order**

```bash
cd /home/dickson/Desktop/personal/qkt
git tag -a v0.1.0   d0bf3b8 -m "phase 1 — trading engine MVP"
git tag -a v0.2.0   6da8cab -m "phase 2a — event bus"
git tag -a v0.2.1   7bc92dc -m "phase 2b — candle aggregator"
git tag -a v0.3.0   6e2381f -m "phase 3 — risk and positions"
git tag -a v0.3.1   0cb05a4 -m "phase 3b — pnl and bigdecimal"
git tag -a v0.4.0   54e6b4c -m "phase 4 — backtest harness"
git tag -a v0.5.0   bf25efc -m "phase 5 — indicators"
git tag -a v0.6.0   a3132fe -m "phase 6 — data store"
git tag -a v0.6.1   e826a12 -m "phase 6 — cleanup"
git tag -a v0.7.0   55f4561 -m "phase 7a — live runtime refactor"
git tag -a v0.7.1   44d997f -m "phase 7b — live runtime and warmup"
git tag -a v0.7.2   365a6cd -m "phase 7c — TradingView vendor"
git tag -a v0.7.3   a3f91b6 -m "phase 7d-a — broker abstraction"
git tag -a v0.7.4   cdfba6a -m "phase 7d-b — OrderManager"
git tag -a v0.7.5   81f3a6e -m "phase 7e — Bybit Spot and CompositeBroker"
git tag -a v0.7.6   5fb6ce0 -m "phase 7f — broker connection resilience"
git tag -a v0.7.7   6eb6ec4 -m "phase 7g — reconciliation and balance polling"
git tag -a v0.7.8   7d15f71 -m "phase 7h — derivatives and rate limiting"
git tag -a v0.8.0   850c14a -m "phase 8 — strategy context and pnl attribution"
git tag -a v0.9.0   9f447f8 -m "phase 9 — risk engine"
git tag -a v0.10.0  634b2e3 -m "phase 10 — backtest reporting"
git tag -a v0.10.1  719620b -m "phase 10b — parameter sweep"
```

- [ ] **Step 3: Verify all 22 tags exist locally**

```bash
git -C /home/dickson/Desktop/personal/qkt tag -l 'v0.*' | wc -l
git -C /home/dickson/Desktop/personal/qkt tag -l 'v0.*' | sort -V
```

Expected: count of 22; sorted list shows `v0.1.0` through `v0.10.1`.

- [ ] **Step 4: Push all tags to origin**

```bash
git -C /home/dickson/Desktop/personal/qkt push origin --tags
```

Expected: 22 tags pushed; remote acknowledges.

If any tag push fails (e.g., a tag with the same name already exists on origin from earlier ad-hoc tagging), the command exits non-zero. **Stop and ask the user** — never `--force` a tag push.

---

### Task 14: Author 22 GitHub Releases

**Files:** none in working tree (release entries are GitHub-side).

Requires `gh` CLI authenticated to the repo (`gh auth login` if not).

The 12 phases with changelogs use `--notes-file`; the 10 earlier phases use `--notes` with the merge commit subject.

- [ ] **Step 1: Verify gh CLI is authenticated**

```bash
gh auth status
```

Expected: shows `Logged in to github.com as elitekaycy` (or similar).

If not, run `gh auth login` interactively.

- [ ] **Step 2: Author releases for phases 1-6 (no changelog file)**

```bash
cd /home/dickson/Desktop/personal/qkt

gh release create v0.1.0 --title "v0.1.0 — phase 1 trading engine MVP" \
  --notes "Core engine MVP. Tick → Engine → Strategy → Signal → Order → MockBroker → Trade. Deterministic foundations, mock broker, sample strategy."

gh release create v0.2.0 --title "v0.2.0 — phase 2a event bus" \
  --notes "Event bus introduced. Multi-strategy support; deterministic event ordering via SequenceGenerator."

gh release create v0.2.1 --title "v0.2.1 — phase 2b candle aggregator" \
  --notes "CandleAggregator and CandleEvent. Strategies can subscribe to bar-close events."

gh release create v0.3.0 --title "v0.3.0 — phase 3 risk and positions" \
  --notes "PositionTracker, RiskEngine with submission rules (e.g. MaxPositionSize)."

gh release create v0.3.1 --title "v0.3.1 — phase 3b pnl and bigdecimal" \
  --notes "PnLCalculator and BigDecimal money throughout. Realized + unrealized P&L."

gh release create v0.4.0 --title "v0.4.0 — phase 4 backtest harness" \
  --notes "Backtest harness over historical ticks. Deterministic replay using FixedClock."

gh release create v0.5.0 --title "v0.5.0 — phase 5 indicators" \
  --notes "Indicator framework with EMA, SMA, RSI, ATR. IndicatorMap for multi-symbol composition."

gh release create v0.6.0 --title "v0.6.0 — phase 6 data store" \
  --notes "On-disk content-addressable data store at ~/.qkt/data/. Dukascopy auto-fetch via bundled script."

gh release create v0.6.1 --title "v0.6.1 — phase 6 cleanup" \
  --notes "Cleanup follow-up to phase 6: store hygiene, bug fixes."
```

- [ ] **Step 3: Author releases for phases 7+ (with changelog files)**

For each phase that has a `docs/phases/phase-*.md` file, use `--notes-file`. The exact changelog filename is provided below.

```bash
cd /home/dickson/Desktop/personal/qkt

# Phase 7a-c don't have a dedicated per-sub-phase changelog;
# only phase-7-live-runtime.md exists, covering 7a/7b/7c collectively.
gh release create v0.7.0 --title "v0.7.0 — phase 7a live runtime refactor" \
  --notes-file docs/phases/phase-7-live-runtime.md

gh release create v0.7.1 --title "v0.7.1 — phase 7b live runtime and warmup" \
  --notes "Phase 7b builds on phase 7a's live runtime refactor; see docs/phases/phase-7-live-runtime.md for the unified changelog."

gh release create v0.7.2 --title "v0.7.2 — phase 7c TradingView vendor" \
  --notes "Phase 7c adds the TradingView live vendor; see docs/phases/phase-7-live-runtime.md for the unified changelog."

gh release create v0.7.3 --title "v0.7.3 — phase 7d-a broker abstraction" \
  --notes-file docs/phases/phase-7d-broker-and-orders.md

gh release create v0.7.4 --title "v0.7.4 — phase 7d-b OrderManager" \
  --notes "Phase 7d-b adds OrderManager and composite orders; see docs/phases/phase-7d-broker-and-orders.md for the unified changelog."

gh release create v0.7.5 --title "v0.7.5 — phase 7e Bybit Spot and CompositeBroker" \
  --notes-file docs/phases/phase-7e-bybit-and-composite.md

gh release create v0.7.6 --title "v0.7.6 — phase 7f broker connection resilience" \
  --notes-file docs/phases/phase-7f-broker-resilience.md

gh release create v0.7.7 --title "v0.7.7 — phase 7g reconciliation and balance polling" \
  --notes-file docs/phases/phase-7g-reconciliation-and-balances.md

gh release create v0.7.8 --title "v0.7.8 — phase 7h derivatives and rate limiting" \
  --notes-file docs/phases/phase-7h-derivatives-and-rate-limit.md

gh release create v0.8.0 --title "v0.8.0 — phase 8 strategy context and pnl attribution" \
  --notes-file docs/phases/phase-8-strategy-context-and-pnl-attribution.md

gh release create v0.9.0 --title "v0.9.0 — phase 9 risk engine" \
  --notes-file docs/phases/phase-9-risk-engine.md

gh release create v0.10.0 --title "v0.10.0 — phase 10 backtest reporting" \
  --notes-file docs/phases/phase-10-backtest-reporting.md

gh release create v0.10.1 --title "v0.10.1 — phase 10b parameter sweep" \
  --notes-file docs/phases/phase-10b-parameter-sweep.md \
  --latest
```

The `--latest` flag on `v0.10.1` marks it as the "Latest release" on the repo's home page.

If any `gh release create` fails because a release already exists at that tag (e.g., previous attempts), `gh release edit` can update; `gh release delete <tag> --cleanup-tag` if the tag itself needs to go. Stop and ask the user before deleting.

- [ ] **Step 4: Verify all 22 releases**

```bash
gh release list --limit 30
```

Expected: 22 releases listed, `v0.10.1` marked Latest.

- [ ] **Step 5: No commit (this task is purely external)**

---

### Task 15: Backfill Phase 11a's own merge commit SHA

**Files:**
- Modify: `docs/phases/phase-11a-oss-baseline.md`

After merging Phase 11a, the changelog still says `Merge commit: filled in at merge time.` Replace that with the actual SHA.

- [ ] **Step 1: Find the Phase 11a merge SHA**

```bash
git -C /home/dickson/Desktop/personal/qkt log --merges --oneline | grep "phase 11a"
```

Expected: one line, format `<SHA> merge: phase 11a oss baseline`.

- [ ] **Step 2: Edit the changelog**

In `docs/phases/phase-11a-oss-baseline.md`, replace `- Merge commit: filled in at merge time.` with `- Merge commit: \`<SHA>\``.

- [ ] **Step 3: Commit and push**

```bash
git add docs/phases/phase-11a-oss-baseline.md
git commit -m "docs: link phase 11a changelog to merge commit"
git push origin main
```

The pre-push hook runs precheck. All should pass.

- [ ] **Step 4: Tag and release v0.11a.0 (or v0.11.0)**

Wait — Phase 11a is the OSS baseline, not a numbered engine phase. Per the spec's mapping table it's not in the version sequence. The next engine phase (Phase 11 DSL) will be `v0.11.0`. Phase 11a does not get its own tag; it's repo housekeeping that lives on main and is observable via the merge commit. **Skip the tag step for 11a.**

If you disagree with this and want a tag (e.g., `v0.10.2` to mark "11a shipped"), the rule from `docs/release-process.md` allows patch bumps for non-phase work. Stop and ask the user before tagging.

---

## Self-Review Notes

Spec coverage check:

| Spec section | Plan task |
|---|---|
| §2 Goals — LICENSE | Task 1 |
| §2 Goals — README rewrite | Task 9 |
| §2 Goals — CONTRIBUTING update | Task 8 |
| §2 Goals — CODE_OF_CONDUCT | Task 3 |
| §2 Goals — SECURITY | Task 2 |
| §2 Goals — CI workflow | Task 4 |
| §2 Goals — Issue + PR templates | Tasks 5, 6 |
| §2 Goals — Release process doc | Task 7 |
| §2 Goals — 22 backfill tags | Task 13 |
| §2 Goals — 22 GitHub Releases | Task 14 |
| §13 Sequencing | Tasks 1-9 in order |
| §15 Success criteria | Task 11 (verification) + Task 14 (releases visible) |

Type-consistency check:
- Apache 2.0 LICENSE format consistent across Task 1 (curl-fetch + prepend copyright) and §4 of the spec.
- CI workflow YAML matches spec §9 verbatim.
- Issue templates match spec §10 verbatim.
- Release process doc content matches spec §11 verbatim.
- Tag list in Task 13 matches spec §12 mapping table exactly.

Placeholder scan: the only literal `TODO`/`FIXME` references are inside the precheck step description ("scan for stray TODO/FIXME"), which is the precheck's purpose, not a plan placeholder.

No code-step has a "TBD" or "implement later" — every step has runnable commands or full file content.
