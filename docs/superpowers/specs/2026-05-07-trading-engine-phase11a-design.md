# Phase 11a — Open-Source Baseline

**Status:** Design draft.
**Predecessor:** Phase 10b (parameter sweep).
**Successor:** Phase 11 (DSL), Phase 10c (walk-forward).

---

## 1. Mission

qkt has shipped 22 phases of engine work and is pushed to a public GitHub repository, but the repo has no LICENSE, no CI, a stale README from Phase 1, no Code of Conduct, no security policy, no release/versioning policy, and no tags. Phase 11a closes the open-source posture gap so that anyone landing on the repo can understand what qkt is, run it, contribute, file bugs, and report security issues — and so that every phase merge is discoverable as a tagged GitHub Release.

This is meta-work, not engine work. No production source code changes. The deliverable is documentation, repo plumbing, CI, and 22 backfill tags.

---

## 2. Goals

- **License: Apache 2.0.** `LICENSE` file at root, copyright "2026 Dickson Anyaele." Apache chosen for the patent grant + Java/Kotlin ecosystem default.
- **README rewrite.** Reflect Phase 10b state, not Phase 1. Cookbook-style quickstart, feature list linking to phase changelogs, current source tree.
- **CONTRIBUTING.md update.** Add release-flow section, refresh phase examples, link to `docs/release-process.md`.
- **CODE_OF_CONDUCT.md.** Contributor Covenant 2.1 verbatim with maintainer email.
- **SECURITY.md.** Disclosure email + 7-day acknowledgment SLA + scope statement.
- **CI workflow.** `.github/workflows/check.yml` runs `./gradlew check` on push to main and on PR. Single job, Ubuntu, JDK 21.
- **Issue + PR templates.** Bug report, feature request, PR template mirroring CONTRIBUTING.md.
- **Release process doc.** `docs/release-process.md` documents SemVer 0.x.y, tagging procedure, GitHub Release authoring.
- **22 backfill tags.** Annotated tags on every named phase merge commit, mapped to SemVer 0.X.Y where minor = phase number and patch = sub-phase letter.
- **22 GitHub Releases.** Manual creation via `gh release create`. Phases 7+ get the phase changelog as the body; phases 1-6 get the merge commit subject.

## Non-goals

- **No publishing to Maven Central / JitPack / GitHub Packages.** Consumers clone + build. Publishing is a separate decision tree (group ID, signing, Sonatype account) and not justified until someone asks.
- **No release automation.** No `release.yml` workflow. Tags pushed manually, releases authored manually. Add automation when manual gets painful.
- **No docs site / GitHub Pages.** `docs/` in the repo is enough for now. Mkdocs / Hugo deployment is a Phase 11b polish if anyone wants it.
- **No top-level CHANGELOG.md aggregator.** `docs/phases/` is the authoritative timeline; aggregating into a top-level file would duplicate or stale-link. README points to `docs/phases/`.
- **No retroactive changelog writing for phases 1-6.** Those predate the changelog convention. Their tags use the merge commit subject as the release body.
- **No commercial licensing setup, dual-licensing, or CLA.** Apache 2.0 is enough; CLA/dual-license can be added later if a real contributor flow emerges.
- **No matrix CI** (multiple OSes, multiple JDKs). qkt has one supported JDK (21) and runs in any standard JVM environment. Matrix is overhead without value.
- **No badge inflation.** README gets the standard "build status" badge from CI. No "code quality," "lines of code," or other vanity badges.
- **No sponsor / funding files.** `FUNDING.yml` is a future decision.

---

## 3. File layout

### New files

```
qkt/
├── LICENSE                          # Apache 2.0 verbatim + 2026 Dickson Anyaele
├── CODE_OF_CONDUCT.md               # Contributor Covenant 2.1
├── SECURITY.md                      # disclosure policy
├── .github/
│   ├── workflows/
│   │   └── check.yml                # gradle check on push and PR
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.md
│   │   └── feature_request.md
│   └── PULL_REQUEST_TEMPLATE.md
└── docs/
    └── release-process.md           # versioning + tagging policy
```

### Modified files

- `README.md` — full rewrite (Phase 1 → Phase 10b reality).
- `CONTRIBUTING.md` — add release-flow section, refresh phase examples.

### No source code changes

`src/` is untouched. `build.gradle.kts` is untouched (no publishing setup). `scripts/` is untouched.

---

## 4. License

Apache 2.0 verbatim from <https://www.apache.org/licenses/LICENSE-2.0.txt>, with the standard copyright header at top:

```
Copyright 2026 Dickson Anyaele

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

The full LICENSE file is the long-form Apache 2.0 text (~200 lines), which the implementation plan inlines verbatim.

No per-file license headers. The root `LICENSE` covers the entire repo per Apache 2.0 §4 standard practice.

---

## 5. README structure

Replace top-to-bottom. Outline:

1. **Heading + tagline.** "qkt — an event-driven trading engine in Kotlin with backtest replay, parameter sweeps, attribution-aware risk, and a future SQL-like DSL."
2. **Status.** Pre-1.0, current version `v0.10.1`, link to GitHub Releases. Brief note that the engine is functional but pre-stable; breaking changes happen in minor releases until 1.0.
3. **Features.** Bullet list, each linking to the relevant phase changelog:
   - Tick + candle pipeline with deterministic event bus
   - Multi-strategy support with per-strategy P&L attribution (Phase 8)
   - Risk engine: equity tracking, drawdown halts, daily loss halts (Phase 9)
   - Bybit Spot + Linear (USDT) live trading with reconciliation, rate limiting, halts (Phase 7e–7h)
   - Backtest replay engine with full reporting: equity curves, Sharpe, Calmar, profit factor (Phase 10)
   - Parameter sweep with sequential or fixed-pool parallel execution (Phase 10b)
   - TradingView-vendor live data (anonymous, free-tier) for paper trading (Phase 7c)
   - On-disk content-addressable data store with Dukascopy auto-fetch (Phase 6)
4. **Quickstart.** Five lines of code running a `Backtest.fromStore(...)` with the sample data fixture, printing total P&L. Self-contained, copy-pasteable.
5. **Architecture in one paragraph + ASCII diagram.** Pipeline `Tick → Engine → Strategy → Signal → Order → Broker → Trade` plus a paragraph on determinism, single-thread invariant, and read/write split. Link to phase changelogs and design specs for depth.
6. **Repository layout.** `src/main/kotlin/com/qkt/` tree expanded to include `app`, `backtest`, `broker`, `bus`, `candles`, `common`, `engine`, `events`, `execution`, `marketdata`, `pnl`, `positions`, `risk`, `strategy`. One-line description per package.
7. **Build and run.** Three-step block: `./gradlew build`, `./gradlew test`, `./gradlew run`. Plus `./gradlew runLiveDemo` for the TradingView demo. Plus `./scripts/precheck.sh` and `./scripts/install-hooks.sh`.
8. **Getting real data.** Condensed from current README — Dukascopy script, BYO CSV, sample data fixture. Three short paragraphs.
9. **Live trading.** Brief paragraph on `LiveSession` + TradingView vendor. Link to `docs/phases/phase-7-live-runtime.md` and `docs/phases/phase-7c-tradingview-vendor.md` (if exists, else the merge commit).
10. **Documentation map.** Pointers to:
    - `docs/phases/` — per-phase changelogs (the authoritative "what's in qkt today" reference)
    - `docs/superpowers/specs/` — design specs per phase
    - `docs/superpowers/plans/` — implementation plans per phase
    - `docs/release-process.md` — versioning + tagging
    - `CONTRIBUTING.md` — how to contribute
    - `SECURITY.md` — vulnerability disclosure
11. **License.** "Apache 2.0 — see [LICENSE](LICENSE)."

Length target: 200-250 lines. Existing 177 is close; the rewrite trades stale Phase 1 content for current Phase 1-10b reality.

---

## 6. CONTRIBUTING.md updates

The current doc is mostly accurate; targeted edits, not a rewrite.

### 6.1 Refresh phase examples

Replace stale phase examples (`phase2-event-bus`) with current ones (`phase11a-oss-baseline`, `phase10b-parameter-sweep`). The convention itself doesn't change.

### 6.2 Add a release-flow section

After the "Pull requests" section, add:

```markdown
## Releases

After a `merge: phase X ...` commit lands on main, the maintainer:

1. Tags the merge commit: `git tag -a v0.X.Y -m "phase X — <description>"`.
2. Pushes the tag: `git push origin v0.X.Y`.
3. Creates a GitHub Release using the tag, with the corresponding
   `docs/phases/phase-<N>-<topic>.md` content as the release body.

See `docs/release-process.md` for the full process and version-number
conventions.
```

### 6.3 No structural changes

The skill-reference, branching, commit, code-style, and testing sections are unchanged.

---

## 7. CODE_OF_CONDUCT.md

Contributor Covenant 2.1 verbatim from <https://www.contributor-covenant.org/version/2/1/code_of_conduct/>. Email field set to `dicksonanyaele1234@gmail.com`. No edits to the body — adopting standard signals welcoming community without inventing custom policy.

---

## 8. SECURITY.md

```markdown
# Security Policy

## Reporting a vulnerability

Email dicksonanyaele1234@gmail.com with "qkt security" in the subject.

We aim to acknowledge within 7 days and provide a fix or mitigation timeline
within 14 days. Please do not file public GitHub issues for security reports.

## Scope

In scope:
- Code execution, deserialization, or path traversal in the engine, broker, or
  data store layers
- Credential leakage via logs, exception messages, or persisted state
- Order-routing logic that can produce unintended live-broker actions

Out of scope:
- Strategy bugs (incorrect P&L is a strategy concern, not an engine
  vulnerability)
- DoS via crafted market data — we accept that adversarial feeds can OOM us
- Issues in transitive dependencies that do not affect qkt's actual behavior

## Supported versions

Pre-1.0 releases: only the latest minor receives security fixes. Users on older
minors should upgrade to the latest. Once 1.0 ships, this policy will be
revisited.
```

---

## 9. CI workflow

`.github/workflows/check.yml`:

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

- Single job. No matrix.
- Linux only — no need for macOS/Windows runners; nothing platform-specific in qkt.
- JDK 21 only — single supported JDK.
- `--no-daemon` is the GitHub Actions convention (no daemon needed for single-shot CI runs).
- `gradle/actions/setup-gradle@v3` handles dependency + build cache automatically.

After this lands, the README's Status section gets a CI badge:

```markdown
[![check](https://github.com/elitekaycy/qkt/actions/workflows/check.yml/badge.svg)](https://github.com/elitekaycy/qkt/actions/workflows/check.yml)
```

---

## 10. Issue + PR templates

### 10.1 `.github/ISSUE_TEMPLATE/bug_report.md`

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

### 10.2 `.github/ISSUE_TEMPLATE/feature_request.md`

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

### 10.3 `.github/PULL_REQUEST_TEMPLATE.md`

Mirror the CONTRIBUTING.md / qkt skill §5 PR description template:

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

---

## 11. Release process doc

`docs/release-process.md`:

```markdown
# Release process

## Versioning

SemVer 0.x.y while pre-1.0:

- **minor (`0.X.0`)** — phase number. Phase 10 → `0.10.x`. Phase 11 → `0.11.x`.
- **patch (`0.X.Y`)** — sub-phase letters (a/b/c/d-a/d-b/...) in chronological
  order, or hotfixes between phases. Phase 10 = `0.10.0`, Phase 10b = `0.10.1`,
  a hypothetical Phase 10c hotfix = `0.10.2`.

Breaking changes are acceptable in minor bumps until 1.0. This matches the
"no backwards compatibility cruft" stance documented in the qkt skill.

Once a stable public DSL ships and the API is documented as stable, we bump to
`1.0.0` and standard SemVer rules apply (no breaking changes in minor).

## Tagging

After a `merge: phase X ...` commit lands on main:

```bash
git checkout main && git pull
git tag -a v0.X.Y -m "phase X — <short description>"
git push origin v0.X.Y
```

Tags are annotated and immutable. **Never** force-update or delete a published
tag. If you push a wrong tag, immediately push a corrected next-patch tag and
note the mistake in the release notes.

## GitHub Release

1. Open <https://github.com/elitekaycy/qkt/releases/new>.
2. Tag: select the just-pushed `v0.X.Y` tag.
3. Title: `v0.X.Y — phase X <description>`.
4. Description: paste the contents of `docs/phases/phase-<N>-<topic>.md` if
   such a file exists, else the merge commit subject.
5. Mark as "Latest release" if it is.

## Hotfix policy

Pre-1.0 hotfixes:
1. Fix on a `fix-<short-description>` branch off main.
2. Merge via `--no-ff` with `merge: fix <description>`.
3. Tag as the next patch on the current minor: e.g. if main is at `v0.10.1`,
   the hotfix is `v0.10.2`.
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

Intermediate maintenance merges (TradingView fixes, onTick/onCandle refactor)
are not tagged — they are not user-facing milestones.
```

---

## 12. Backfill tag plan

Tags are pushed in chronological order so the GitHub Releases page shows the
project history correctly. Each tag is annotated; messages reuse the merge
commit subject for consistency.

```bash
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
git push origin --tags
```

After tags are pushed, GitHub Releases are created via `gh release create`:

- Phases with changelogs (7+, ten total) use the changelog file as the body.
- Phases without changelogs (1-6, twelve total) use the merge commit subject.

Example:

```bash
gh release create v0.10.1 \
  --title "v0.10.1 — phase 10b parameter sweep" \
  --notes-file docs/phases/phase-10b-parameter-sweep.md
```

---

## 13. Sequencing

The implementation plan executes in this order to minimize cross-references
breaking mid-stream:

1. `LICENSE` (no deps).
2. `SECURITY.md`, `CODE_OF_CONDUCT.md` (no deps).
3. `.github/workflows/check.yml` (no deps; runs on next push).
4. `.github/ISSUE_TEMPLATE/bug_report.md`, `feature_request.md`, `PULL_REQUEST_TEMPLATE.md` (no deps).
5. `docs/release-process.md` (no deps).
6. `CONTRIBUTING.md` update (refers to `docs/release-process.md`).
7. `README.md` rewrite (refers to everything above).
8. Branch merge to main.
9. Tag backfill — 22 annotated tags pushed in chronological order.
10. GitHub Release creation — 22 manual entries via `gh release create`.

---

## 14. Risk

**Low.**

- All file changes are additive except `README.md` (full rewrite) and `CONTRIBUTING.md` (targeted edits). No source code changes.
- CI workflow is a separate file; if it has bugs, they show up on the next push and don't break local development.
- Tags are immutable but only land after the branch merges, so a malformed tag is caught during review.
- Apache 2.0 is the most-litigated open-source license; safe baseline.
- The 22 backfill tags point to existing merge commits — they don't move history.

---

## 15. Success criteria

After Phase 11a merges:

- A stranger landing on `https://github.com/elitekaycy/qkt` can:
  - Read the README and know what qkt is, what it does, and how to run it.
  - See a green build badge from CI.
  - Read CONTRIBUTING.md to understand the workflow.
  - File a bug report or feature request via the issue templates.
  - Find the LICENSE and SECURITY policy.
  - Browse 22 GitHub Releases corresponding to every shipped phase.
- `./gradlew check` passes in CI on every push to main and on every PR.
- The current commit on main is tagged `v0.10.1` and visible on the Releases page with the phase 10b changelog as the body.
- `docs/release-process.md` documents the tagging procedure for the next phase merge — no need to re-derive the process.

---

## 16. Deferred / Phase 11b candidates

These are explicitly **not** in this phase:

- **Release automation** (`.github/workflows/release.yml`) — auto-tag and auto-publish on phase-merge label.
- **Maven Central / JitPack publishing** — requires group ID, signing, Sonatype account decisions.
- **Docs site** — mkdocs / Hugo deployment to GitHub Pages.
- **Top-level CHANGELOG.md aggregator** — script to concatenate `docs/phases/*.md`.
- **Funding / sponsorship setup** — `FUNDING.yml`, GitHub Sponsors enrollment.
- **Dual-licensing or CLA** — only relevant once contributors appear.
- **Multi-OS / multi-JDK CI matrix** — overhead without justification.
- **Code-quality / coverage badges** — vanity metrics; the CI badge alone is enough.
- **Discussions / forum integration** — issues are sufficient until discussion volume justifies separation.
