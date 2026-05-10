# Documentation plan — internal, external, production-grade live docs

**Status:** Design / brainstorm
**Author:** elitekaycy
**Date:** 2026-05-10

---

## 1. Goal

Define the documentation system qkt needs to (a) onboard end-users from zero to running their first strategy, (b) train contributors on the codebase, (c) serve as a production reference for operators running qkt in production. The system has to be cheap to keep current — docs that drift from code are worse than no docs.

Three audiences. Three content surfaces. One repo, one toolchain, one deploy.

---

## 2. Audiences

### A1 — End-user (trader)

Wants to:
- Install qkt and run their first backtest
- Write a strategy in the DSL
- Deploy to paper, then to a real broker
- Monitor and audit live runs
- Understand what guarantees the engine provides (and doesn't)

Doesn't care about:
- Internal class layout
- Phase history
- Compiler internals

Reads: tutorials, how-to guides, DSL reference, CLI reference, broker setup guides, troubleshooting.

### A2 — Contributor

Wants to:
- Understand the architecture
- Find the right place to add a feature
- Run the tests and get green
- Follow the project's conventions for commits, PRs, phases
- Read the AST → compiler → runtime data flow

Reads: architecture diagrams, KDoc/Dokka API reference, development conventions, phase-spec/plan archive, contributing guide.

### A3 — Operator (production)

Wants to:
- Stand up the daemon + gateway in prod
- Configure brokers and profiles
- Wire monitoring and alerting
- Diagnose live issues (broker disconnect, position drift, log volume)
- Tune for performance

Reads: deployment guides, operational runbooks, monitoring/alerting hooks, troubleshooting matrix, broker-specific gotchas.

---

## 3. Information architecture

Single docs site with five top-level sections, mapped to audiences:

```
qkt docs
├── Get started        (A1, A3)  — install, quickstart, first deploy
├── Tutorials          (A1)      — walkthroughs (momentum, portfolio, MT5 deploy)
├── How-to guides      (A1, A3)  — task-oriented recipes
├── Reference          (A1, A2)  — DSL grammar, CLI, config, KDoc API
├── Concepts           (A1, A2)  — engine architecture, backtest model, broker integration
├── Operations         (A3)      — deployment, monitoring, troubleshooting
└── Contributing       (A2)      — conventions, phase workflow, dev setup
```

Following the [Diátaxis](https://diataxis.fr/) framework: tutorials (learning), how-tos (problem-solving), reference (information), explanation (understanding). Each piece of content has one job — no merged tutorial-and-reference pages.

### Detailed sitemap

```
/get-started/
    index                         5-minute install + first backtest
    deploy-paper                  Paper-trade a strategy via the daemon
    deploy-mt5                    Live trade via Exness/MT5

/tutorials/
    momentum-strategy             Build an EMA-cross momentum strategy from scratch
    risk-engine                   Add stop-loss + drawdown halts
    portfolio                     Compose strategies via PORTFOLIO files
    multi-broker                  Trade Bybit + MT5 in one strategy
    backtest-to-production        Audit a strategy with the HTML report, ship it live

/how-to/
    write-a-strategy              DSL basics, indicators, conditions
    handle-bracket-orders         SL + TP semantics, fallback path
    use-stack-pyramiding          STACK action for layered entries
    configure-mt5-broker          qkt.config.yaml + extends + env vars
    multi-account-mt5             Same broker, multiple logins
    audit-tick-feed               qkt audit-ticks workflow
    interpret-the-html-report     Reading equity curves, DD tables, MC fan
    debug-a-rejected-order        Risk halts, broker rejections, log levels
    rotate-logs                   logback override patterns

/reference/
    dsl-grammar                   Full BNF + every keyword
    cli-commands                  Every qkt subcommand with flags
    config-schema                 qkt.config.yaml fields, defaults, env vars
    api/                          Dokka-generated KDoc API reference (linked subdomain)

/concepts/
    architecture                  Tick → strategy → signal → order → broker → fill
    determinism                   Why backtest = live-paper given same ticks
    backtest-model                Equity, drawdown, slippage assumptions
    broker-integration            Capability matrix, fallback paths, magic semantics
    risk-engine                   Halt rules, attribution, recovery
    portfolios                    Supervisor + child gating model

/operations/
    deploy-docker                 docker-compose.yml walkthrough
    deploy-systemd                qkt as a systemd service (non-Docker)
    monitor                       /status, /logs, control plane endpoints
    alert                         What to page on, hooks
    upgrade                       Version migration + rollback
    troubleshoot                  Symptom → cause matrix
    capacity                      How many strategies per daemon, port allocation

/contributing/
    development-setup             Build, test, IDE, hooks
    conventions                   Commits, branches, code style (links qkt SKILL)
    phase-workflow                Brainstorm → spec → plan → ship → changelog
    architecture-deep-dive        Engine internals for code contributors
    adding-a-broker               Step-by-step: implement Broker, register profile
```

---

## 4. Content responsibilities — what lives where

| Content | Source | Audience |
|---|---|---|
| Tutorials, how-tos, concepts, ops | `docs/` markdown files | A1, A3 |
| API reference (Dokka) | KDoc on public Kotlin classes | A2 |
| DSL grammar | `docs/reference/dsl-grammar.md` (markdown) — co-edited with Parser changes | A1, A2 |
| CLI reference | Auto-generated from `qkt --help` output (script in `scripts/gen-cli-docs.sh`) | A1 |
| Phase changelogs | `docs/phases/phase-*.md` (already shipped) | A1, A2 |
| Architecture diagrams | `docs/concepts/*.md` with embedded Mermaid | A1, A2 |
| Spec + plan archive | `docs/superpowers/specs/`, `docs/superpowers/plans/` (already shipped) | A2 |
| Contributing | `docs/contributing/*.md` + the qkt SKILL | A2 |
| Examples | `examples/` directory at repo root, surfaced in docs | A1 |

**Single source of truth per concept.** A change to the bracket-fallback path updates `concepts/broker-integration.md` AND adds a note to the relevant phase changelog AND tweaks the KDoc on `OrderManager.submitBracketFallback`. Reviewers gate PRs on docs touching the same concept.

---

## 5. Tooling stack

### MkDocs Material — end-user / external docs

- Static site generator. Markdown source, fast build, excellent default theme.
- Built-in instant search (no Algolia needed initially).
- Dark mode, code copy, tabs (e.g., "DSL" vs "Kotlin DSL"), admonitions.
- Versioned docs via `mike` plugin (preserves prior versions at `/v0.21.0/`).
- Mermaid + Pymdown extensions for diagrams and rich code blocks.

### Dokka — Kotlin API reference

- Official Kotlin doc generator.
- Reads KDoc comments + signatures.
- HTML output linked from the MkDocs site at `/api/`.
- Run as a Gradle task; output to `build/dokka/html/`.
- Multi-module aware; one site for the whole codebase.

### Mermaid — diagrams as code

- Sequence diagrams, flowcharts, ER, class.
- Renders client-side in MkDocs Material.
- Diagrams live next to the prose they describe; PRs include both.

### GitHub Pages — hosting

- Free for OSS repos.
- Custom domain optional later (`docs.qkt.dev`?).
- Auto-deployed via GitHub Actions on push to main.

### GitHub Actions — build + deploy

- One workflow: `on: push to main` → build MkDocs site + Dokka API → deploy to `gh-pages` branch.
- Validates: every link resolves, every code sample compiles (KDoc + a test that runs every fenced ```kotlin block — a future enhancement).

---

## 6. Authoring workflow

### How docs stay current

1. **PR-level discipline.** Every PR that changes user-facing behavior touches docs in the same commit. Reviewers gate on this.
2. **Phase changelog convention.** Already established (qkt SKILL §6) — every phase gets a `docs/phases/phase-N.md` covering Summary, What's new, Migration, Cookbook, Testing, Limitations, References. This rolls up into the docs site automatically.
3. **CLI auto-gen.** A script captures `qkt --help` output for every subcommand into `docs/reference/cli-commands.md`. CI fails if the file is out of sync with reality.
4. **DSL grammar auto-gen.** A test extracts the parser's accepted grammar via reflection / source AST and asserts the file matches. (Future enhancement; v1 keeps it manually maintained.)
5. **KDoc lint.** ktlint (already in CI) checks KDoc presence on public types. Missing KDoc fails the build.

### Local preview

```sh
mkdocs serve              # localhost:8000 with hot reload
./gradlew dokkaHtml       # rebuild API reference
```

### Style guide

- One H1 per page, set by the title.
- Short sentences. Active voice. "Strategies fill at MT5 prices" not "MT5 prices are where strategies fill at".
- Code samples are runnable. If you show `qkt deploy`, the surrounding context shows the daemon already started.
- Every page ends with **References** linking to specs/plans/source.
- Sentence case for headings (matches existing phase changelogs).

---

## 7. Examples + demos

### `examples/` directory (at repo root)

```
examples/
├── strategies/
│   ├── momentum-cross.qkt           Simple EMA cross
│   ├── volatility-breakout.qkt      ATR-driven entries
│   ├── multi-symbol-portfolio.qkt   PORTFOLIO with 3 children
│   └── mt5-live.qkt                 Production-grade live strategy
├── docker/                          (already exists)
└── notebooks/                       Jupyter notebooks loading qkt result.json
```

Each `.qkt` file ships with a `.md` sibling explaining what it does, why it's structured that way, and which docs page covers the relevant concepts.

### Demos

- **Recorded GIFs** in tutorials showing CLI flow (`qkt deploy`, `qkt list`, `qkt logs --follow`). Recorded with `asciinema` then converted to GIF for embedding.
- **Sample HTML reports** in `examples/sample-reports/` — operators can preview what they'd produce without running a backtest first.
- **Reference deployment** at `examples/docker/` (already exists) — extended to include the full compose stack.

### Live playground (deferred)

Browser-based DSL playground that compiles + runs a backtest against fixed sample data. High effort; deferred to v2 of the docs system.

---

## 8. Phased rollout

### v1 (next phase — Phase 21 candidate)

Scope: ship the docs site infrastructure, migrate existing content, fill the highest-value gaps.

- MkDocs Material setup at `mkdocs.yml`.
- Dokka Gradle plugin + KDoc audit.
- GitHub Actions workflow building + deploying to `gh-pages` on push to main.
- Migrate existing `docs/` contents into the new IA.
- Land:
  - Get-started: install, quickstart (already exists at root, link), deploy-paper.
  - Reference: dsl-grammar, cli-commands (auto-gen script), config-schema.
  - Concepts: architecture (with Mermaid diagram), determinism, backtest-model.
  - Operations: deploy-docker (cookbook the new docker-compose.yml).
  - Contributing: development-setup, conventions (link the qkt SKILL).
- Defer: tutorials, how-to guides, deep-dives. Stub pages with "coming soon".

### v2 (one phase later)

Tutorials + how-tos. The high-value end-user content. Each piece written by walking through an actual strategy end-to-end with screenshots.

### v3 (further out)

- Versioning via `mike`.
- Search analytics + content gap analysis.
- Live playground.
- Translation to additional languages if community grows.

---

## 9. Decisions to lock in

| Decision | Default |
|---|---|
| Theme | MkDocs Material |
| API reference generator | Dokka HTML |
| Diagram engine | Mermaid (renders in Material) |
| Hosting | GitHub Pages, project-scoped (`elitekaycy.github.io/qkt/`) |
| Custom domain | Deferred — buy `qkt.dev` later if community grows |
| Search | MkDocs Material built-in (Lunr-based); switch to Algolia DocSearch if size warrants |
| Versioning | Single `latest` for v1; add `mike` versioning when 1.0 ships |
| Code-sample testing | Manual review for v1; KDoc samples auto-tested via `kotlin -script` for v2 |
| Comments policy | KDoc on every public type, package, and top-level function |

---

## 10. Risks

**Medium — docs drift.** The biggest failure mode for any doc system. Mitigation: PR-level discipline + auto-gen for CLI/grammar + every phase changelog. Reviewers gated on it.

**Medium — Dokka output bulk.** A multi-module Kotlin codebase generates large API trees. Mitigation: include only `com.qkt.*` packages, suppress internal-only modules, lazy-load via Material's tabs.

**Low — Mermaid rendering on GitHub vs MkDocs.** Slight syntax differences. Mitigation: use the [`mermaid2` plugin](https://github.com/fralau/mkdocs-mermaid2-plugin) and test both render paths.

**Low — Tutorial maintenance cost.** End-to-end tutorials break when CLI flags change. Mitigation: each tutorial has an integration test that runs the commands; CI fails if the tutorial is stale.

---

## 11. References

- [Diátaxis framework](https://diataxis.fr/) — content typology this plan adopts
- [MkDocs Material](https://squidfunk.github.io/mkdocs-material/) — theme
- [Dokka](https://kotl.in/dokka) — Kotlin API doc generator
- [Mermaid](https://mermaid.js.org/) — diagrams as code
- Existing phase-changelog convention: qkt SKILL §6
- Existing `examples/docker/` — referenced as the prod-deployment cookbook
- Top-level `QUICKSTART.md` — landing page that links into the docs site
