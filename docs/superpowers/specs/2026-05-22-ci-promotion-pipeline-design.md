# CI promotion pipeline — design

**Date:** 2026-05-22
**Status:** approved (brainstorming)

## Context

qkt today has a single integration branch, `main`. Every feature branch merges
straight into `main`, and CI (`check.yml`) runs build + unit tests + ktlint plus
a `smoke` job. There is no staging layer between "tests pass" and "this is the
release-ready branch" — a change that compiles and passes unit tests but breaks
the packaged product (install layout, daemon lifecycle, Docker image) can reach
`main` and only be caught by the `smoke` job on `main` itself.

We want a three-branch promotion pipeline so that by the time a change reaches
`main` it has cleared both a fast unit gate and a full black-box integration
gate, with a human checkpoint before `main`.

## Goals

- Three branches — `dev`, `testing`, `main` — with changes flowing one way.
- `dev` is the working branch: feature branches PR into it; it runs the fast
  "essentials" CI.
- A green `dev` is **automatically** promoted to `testing`.
- `testing` runs a full black-box **integration** CI that mimics a user pulling
  the repo, installing, and exercising the product.
- Promotion of `testing` → `main` is **manual** — triggered by a maintainer,
  so `testing` can be pulled and inspected first.
- `main` re-runs the integration CI as a final confirmation gate.
- Feature-branch PRs run **only the essentials** — not the heavy integration
  suite.

## Non-goals

- No change to the release mechanism — `release.yml` and `docker.yml` continue
  to fire on `v*` tags. Tags are cut from `main`.
- No third-party CI/merge bots (Mergify, etc.).
- No change to commit conventions or the issue-flow.

## Branch model

| Branch | Role | Updated by | Direct commits |
|---|---|---|---|
| `dev` | Integration / working branch. GitHub **default branch**. | Feature-branch PRs (`--no-ff`). | Via PR only. |
| `testing` | Staging. Runs the integration suite. | Auto fast-forward from `dev`. | Never. |
| `main` | Release-ready. Tags + releases cut here. | Manual fast-forward from `testing`. | Never. |

- Promotion is a **fast-forward push** — the exact commits that passed CI move
  to the next branch. No merge commits; `testing` and `main` histories are
  always prefixes of `dev`'s.
- Because nothing commits directly to `testing`/`main`, fast-forward is always
  valid.
- The GitHub **default branch** becomes `dev` so PRs auto-target it and fresh
  clones land on it.

## CI tiers

### Tier 1 — Essentials (`check.yml`, retargeted)

`./gradlew build` — compile + ktlint + unit tests + assemble.

- **Triggers:** `pull_request` targeting `dev`; `push` to `dev`.
- Fast (~3 min). This is the only CI a feature-branch PR runs.
- On a `push` to `dev`, a final `promote-to-testing` job runs.

### Tier 2 — Integration (`integration.yml`, new)

A black-box suite that mimics a user consuming the shipped product. Built on the
existing `tests/smoke-install.sh`, **extended** with a daemon-lifecycle stage.

- **Triggers:** `push` to `testing`; `push` to `main`.
- Stages:
  1. **Build + clean install** — `./gradlew installDist`, copy to a fresh prefix,
     run `qkt --version`, assert it equals `BuildInfo.VERSION`.
  2. **Strategy author flow** — write a `.qkt` strategy, `qkt parse`, `qkt backtest`
     against the bundled sample data, JSON-validate the report.
  3. **Daemon lifecycle** (new) — start `qkt daemon`, `qkt deploy` a strategy,
     assert `qkt list` and `qkt status` see it, then `qkt stop` and
     `qkt daemon stop`. These control-plane checks hard-fail. The strategy
     sources market data from Bybit's public feed (`BYBIT_SPOT:BTCUSDT`, with a
     dummy `BYBIT_API_KEY` to enable the route); a **soft** check looks for
     logged live ticks — a blocked Bybit WebSocket warns rather than fails.
  4. **Docker** — `docker build`, run `qkt --version` in-container, run a
     backtest in-container.
- The identical suite runs again on `main` after manual promotion — the final
  confirmation gate.

`tests/smoke-install.sh` already implements stages 1, 2, and 4. Stage 3
(daemon lifecycle) is new and added to that script; the script's header already
claims "daemon operations" but the body never exercised the daemon.

## Promotion automation

### `dev` → `testing` (automatic)

The Essentials workflow gains a final job:

- `promote-to-testing` — `needs: [build]`, `if: github.event_name == 'push' && github.ref == 'refs/heads/dev'`.
- Fast-forward-pushes `dev`'s SHA to `testing`.

### `testing` → `main` (manual)

A separate workflow, `promote-to-main.yml`:

- Trigger: `workflow_dispatch` only (a maintainer clicks "Run workflow", or runs
  `gh workflow run promote-to-main.yml`).
- Guard: verifies the most recent `integration.yml` run on `testing` concluded
  `success` before promoting. Aborts otherwise.
- Fast-forward-pushes `testing`'s SHA to `main`.

### `main` (terminal)

The Integration workflow runs on `push` to `main` for the confirmation gate.
There is no promotion job past `main`.

## The token problem

A push authenticated with the default `GITHUB_TOKEN` **does not trigger further
workflow runs** (GitHub's recursion guard). If `promote-to-testing` pushed to
`testing` with `GITHUB_TOKEN`, `integration.yml` would never fire on `testing`
and the pipeline would stall.

**Resolution:** promotion pushes use a **fine-grained Personal Access Token**
stored as the repo secret `PROMOTE_TOKEN` (scope: `contents: write` on this
repo). A PAT-authenticated push *does* trigger downstream workflows. Both
`promote-to-testing` and `promote-to-main.yml` use `PROMOTE_TOKEN`.

The maintainer must create this PAT and add it as an Actions secret before the
pipeline works. This is a documented one-time setup step.

## Branch protection

GitHub rulesets:

- `testing`, `main` — block all direct pushes; add a bypass for the
  `PROMOTE_TOKEN` actor (the maintainer / fine-grained PAT owner). Only the
  promotion workflows can move these branches.
- `dev` — require the Essentials check to pass before a PR can merge.

## Failure handling

- **Essentials fails on a PR** — PR cannot merge. Normal.
- **Essentials fails on `dev`** — no promotion; `dev` is red until fixed.
- **Integration fails on `testing`** — no promotion offered; `dev` keeps moving;
  the fix lands via a `dev` PR and the next auto-promotion fast-forwards
  `testing` past the broken state. Self-healing.
- **Integration fails on `main`** — `main` is red. Same commits passed on
  `testing`, so this implies a flake or environment difference; the fix flows
  through `dev` → `testing` → manual promotion again.

## Existing workflows

- `check.yml` — retargeted: triggers change from `main` to `dev`. The `smoke`
  job moves out into `integration.yml`.
- `integration.yml` — new (the smoke job's content, extended).
- `docker.yml` — keeps `push` to `main` + `v*` tags. The `latest` image tag is
  repointed from `{{is_default_branch}}` (now `dev`) to an explicit `main`
  check, so release images still come from `main`.
- `release.yml` — unchanged; fires on `v*` tags cut from `main`.
- `docs.yml` — unchanged; stays on `main` so the published docs reflect the
  release-ready branch.

## qkt skill update

`.claude/skills/qkt/SKILL.md` §2 (Branching strategy) is rewritten for the
three-branch model: feature branches branch from and PR into `dev`; `testing`
and `main` are promotion-only and never receive direct commits; the
`merge: phase <N>` convention applies to feature→`dev` merges. Updated in the
same PR as the workflow changes (living-document protocol).

## One-time setup (maintainer)

1. Create branches `dev` and `testing` from `main`.
2. Set the GitHub default branch to `dev`.
3. Create a fine-grained PAT (`contents: write`), add it as the `PROMOTE_TOKEN`
   Actions secret.
4. Add rulesets for `testing`/`main` (promotion-only) and `dev` (require check).

## Open risks

- The PAT is a credential with write access; it must be fine-grained and
  scoped to this repo only. If leaked, it permits pushes to `testing`/`main`.
- The daemon-lifecycle stage needs a live market feed for its strategy.
  TradingView is unusable from CI runners — it blocks datacenter IPs. Bybit's
  public feed is the source instead. The control-plane assertions are
  network-independent and hard-fail; the live-tick assertion is **soft**,
  because some CI egress policies block `stream.bybit.com`. Once a real
  `testing`-branch run confirms the Bybit WebSocket is reachable from GitHub
  runners, the live-tick assertion can be hardened.
