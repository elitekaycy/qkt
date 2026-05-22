# CI Promotion Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a three-branch CI promotion pipeline — `dev` → `testing` → `main` — where `dev` runs fast unit CI and auto-promotes to `testing`, `testing` runs a full black-box integration CI, and a maintainer manually promotes `testing` to `main`.

**Architecture:** Each CI tier is a GitHub Actions workflow. The essentials workflow (`check.yml`) ends with a job that fast-forward-pushes `dev` to `testing`. The integration workflow (`integration.yml`) runs the extended `tests/smoke-install.sh`. A manual `workflow_dispatch` workflow promotes `testing` to `main`. Promotion pushes use a fine-grained PAT (`PROMOTE_TOKEN`) so they trigger downstream workflows.

**Tech Stack:** GitHub Actions (YAML), Bash (`tests/smoke-install.sh`), Gradle, the `qkt` CLI.

**Spec:** `docs/superpowers/specs/2026-05-22-ci-promotion-pipeline-design.md`

**Working branch:** all code tasks (1–6) commit to the existing `ci-promotion-pipeline` branch. Tasks 7–8 merge it and do the operational setup.

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `tests/smoke-install.sh` | Black-box install/strategy/daemon/Docker smoke. | Modify — add daemon-lifecycle stage. |
| `.github/workflows/integration.yml` | Integration CI on `testing` + `main`. | Create. |
| `.github/workflows/check.yml` | Essentials CI on `dev` + PRs; auto-promote to `testing`. | Modify — retarget, drop `smoke` job, add promote job. |
| `.github/workflows/promote-to-main.yml` | Manual `testing` → `main` promotion. | Create. |
| `.github/workflows/docker.yml` | Image publish. | Modify — repoint `latest` tag to `main`. |
| `.claude/skills/qkt/SKILL.md` | Project conventions. | Modify — rewrite §2 Branching strategy. |

---

## Task 1: Add the daemon-lifecycle stage to the smoke test

**Files:**
- Modify: `tests/smoke-install.sh` (insert a new stage after Step 5, renumber the later steps)

- [ ] **Step 1: Inspect the current daemon CLI flags**

Run:
```bash
./gradlew installDist -q
./build/install/qkt/bin/qkt daemon --help 2>&1 | head -20
./build/install/qkt/bin/qkt deploy --help 2>&1 | head -20
```
Expected: confirm the flags `--state-dir`, `--as`, and the `list`/`status`/`logs`/`stop` subcommands. If a flag name differs from what this task uses (`--state-dir`, `--as`), use the actual flag in Step 2.

- [ ] **Step 2: Insert the daemon-lifecycle stage**

In `tests/smoke-install.sh`, find the end of "Step 5" (the `ok "backtest produced valid JSON report"` line) and the start of "Step 6 — qkt run". Insert this new stage between them:

```bash
# ──────────────────────────────────────────────────────────────────────── #
# Step 6 — Daemon lifecycle
# ──────────────────────────────────────────────────────────────────────── #
say "Step 6: daemon lifecycle (start, deploy, list, status, logs, stop)"
DAEMON_STATE="$SMOKE_DIR/daemon-state"
mkdir -p "$DAEMON_STATE"

"$INSTALL_QKT" daemon --state-dir "$DAEMON_STATE" >>"$LOG_FILE" 2>&1 &
DAEMON_PID=$!

# Wait up to 30s for the daemon to write its control-port file (= ready).
for _ in $(seq 1 30); do
    [ -f "$DAEMON_STATE/control.port" ] && break
    sleep 1
done
[ -f "$DAEMON_STATE/control.port" ] || die "daemon did not become ready (no control.port)"
ok "daemon started (pid $DAEMON_PID)"

"$INSTALL_QKT" deploy "$SMOKE_DIR/strategies/smoke.qkt" --as smoke --state-dir "$DAEMON_STATE" \
    >>"$LOG_FILE" 2>&1 || die "qkt deploy failed"
ok "strategy deployed"

"$INSTALL_QKT" list --state-dir "$DAEMON_STATE" 2>>"$LOG_FILE" | grep -q smoke \
    || die "qkt list does not show the deployed strategy"
ok "qkt list shows the strategy"

"$INSTALL_QKT" status smoke --state-dir "$DAEMON_STATE" >>"$LOG_FILE" 2>&1 \
    || die "qkt status failed"
ok "qkt status responds"

"$INSTALL_QKT" logs smoke --state-dir "$DAEMON_STATE" > "$SMOKE_DIR/strategy.log" 2>>"$LOG_FILE" \
    || die "qkt logs failed"
[ -s "$SMOKE_DIR/strategy.log" ] || die "qkt logs produced no output"
ok "qkt logs produced output"

"$INSTALL_QKT" stop smoke --state-dir "$DAEMON_STATE" >>"$LOG_FILE" 2>&1 \
    || die "qkt stop failed"
ok "strategy stopped"

"$INSTALL_QKT" daemon stop --state-dir "$DAEMON_STATE" >>"$LOG_FILE" 2>&1 \
    || die "qkt daemon stop failed"
ok "daemon stopped"
```

- [ ] **Step 3: Renumber the later step headers**

The inserted stage takes the number "Step 6". Renumber the existing headers below it so the sequence stays monotonic:
- `Step 6 — qkt run` → `Step 7 — qkt run`
- `Step 7 — Docker smoke` → `Step 8 — Docker smoke`
- `Step 8: run a backtest inside the Docker container` → `Step 9: run a backtest inside the Docker container`
- `Step 7-8: Docker stage skipped` → `Step 8-9: Docker stage skipped`

Update both the comment banners and the `say "Step N: ..."` calls. This is text-only renumbering; no logic changes.

- [ ] **Step 4: Run the smoke test to verify the new stage passes**

Run:
```bash
bash tests/smoke-install.sh --no-docker
```
Expected: output includes `== Step 6: daemon lifecycle ... ==` followed by `✓ daemon started`, `✓ strategy deployed`, `✓ qkt list shows the strategy`, `✓ qkt status responds`, `✓ qkt logs produced output`, `✓ strategy stopped`, `✓ daemon stopped`, and finally `✓ smoke test passed`.

If a step fails, read `$SMOKE_DIR/smoke.log` (path printed on failure). Common cause: a wrong CLI flag — correct it from the `--help` output gathered in Step 1. Do not weaken an assertion to make it pass; fix the command.

- [ ] **Step 5: Commit**

```bash
git add tests/smoke-install.sh
git commit -m "test(scripts): add daemon-lifecycle stage to smoke test"
```

---

## Task 2: Create the integration workflow

**Files:**
- Create: `.github/workflows/integration.yml`

- [ ] **Step 1: Write `integration.yml`**

Create `.github/workflows/integration.yml`:

```yaml
name: integration

# Black-box integration suite — mimics a user installing and exercising qkt:
# clean install, qkt --version, author + parse + backtest a strategy, daemon
# lifecycle, Docker build + in-container run. Runs on the promotion targets
# (testing and main). Feature-branch PRs and dev get only the fast essentials
# (check.yml); this heavier suite gates the staging branches.

on:
  push:
    branches: [testing, main]

concurrency:
  group: integration-${{ github.ref }}
  cancel-in-progress: true

jobs:
  integration:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Integration smoke
        run: bash tests/smoke-install.sh
      - name: Upload smoke log on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: smoke-log
          path: /tmp/qkt-smoke.*/smoke.log
          retention-days: 7
          if-no-files-found: ignore
```

- [ ] **Step 2: Verify the YAML parses**

Run:
```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/integration.yml')); print('integration.yml OK')"
```
Expected: `integration.yml OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/integration.yml
git commit -m "chore(ci): add integration workflow for testing and main"
```

---

## Task 3: Retarget the essentials workflow and add auto-promotion

**Files:**
- Modify: `.github/workflows/check.yml` (replace whole file)

- [ ] **Step 1: Replace `check.yml`**

The current `check.yml` triggers on `main`, has a `build` job and a `smoke` job. The `smoke` job's content now lives in `integration.yml` (Task 2), so it is removed here. Triggers move to `dev`. A `promote-to-testing` job is added. Replace the entire file with:

```yaml
name: check

# Fast essentials — compile + ktlint + unit tests — on every PR into dev and
# every push to dev. This is the only CI a feature-branch PR runs. The heavier
# black-box suite is integration.yml, which gates testing and main.
#
# On a push to dev (i.e. a merged PR), a green build auto-promotes dev to
# testing via a fast-forward push. The push uses PROMOTE_TOKEN (a fine-grained
# PAT) rather than GITHUB_TOKEN, because a GITHUB_TOKEN push does not trigger
# downstream workflows — integration.yml must fire on testing.

on:
  push:
    branches: [dev]
  pull_request:
    branches: [dev]

concurrency:
  group: check-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build + test + ktlint
        run: ./gradlew build --no-daemon
      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: |
            build/reports/tests/
            build/test-results/
          retention-days: 7

  promote-to-testing:
    needs: build
    if: github.event_name == 'push' && github.ref == 'refs/heads/dev'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
          token: ${{ secrets.PROMOTE_TOKEN }}
      - name: Fast-forward testing to dev
        run: git push origin "${{ github.sha }}:refs/heads/testing"
```

Note: a plain `git push` of a SHA to `refs/heads/testing` is fast-forward-only — GitHub rejects it if `testing` is not an ancestor. That rejection is the desired safety; do not add `--force`.

- [ ] **Step 2: Verify the YAML parses**

Run:
```bash
python3 -c "import yaml; w=yaml.safe_load(open('.github/workflows/check.yml')); assert set(w['jobs'])=={'build','promote-to-testing'}, w['jobs'].keys(); print('check.yml OK')"
```
Expected: `check.yml OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/check.yml
git commit -m "chore(ci): retarget essentials to dev and auto-promote to testing"
```

---

## Task 4: Create the manual promotion workflow

**Files:**
- Create: `.github/workflows/promote-to-main.yml`

- [ ] **Step 1: Write `promote-to-main.yml`**

Create `.github/workflows/promote-to-main.yml`:

```yaml
name: promote-to-main

# Manual promotion: testing -> main. A maintainer triggers this from the
# Actions tab (or `gh workflow run promote-to-main.yml`) after pulling and
# inspecting testing. It verifies the most recent integration run on testing
# concluded successfully, then fast-forwards main to testing.
#
# The push uses PROMOTE_TOKEN so integration.yml fires again on main as the
# final confirmation gate.

on:
  workflow_dispatch:

permissions:
  contents: read
  actions: read

jobs:
  promote:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          ref: testing
          fetch-depth: 0
          token: ${{ secrets.PROMOTE_TOKEN }}
      - name: Verify integration passed on testing
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          set -e
          conclusion=$(gh run list --workflow integration.yml --branch testing \
            --limit 1 --json conclusion --jq '.[0].conclusion')
          echo "latest integration run on testing: '$conclusion'"
          if [ "$conclusion" != "success" ]; then
            echo "::error::testing's latest integration run is '$conclusion', not 'success' — refusing to promote"
            exit 1
          fi
      - name: Fast-forward main to testing
        run: git push origin HEAD:refs/heads/main
```

- [ ] **Step 2: Verify the YAML parses**

Run:
```bash
python3 -c "import yaml; w=yaml.safe_load(open('.github/workflows/promote-to-main.yml')); assert 'workflow_dispatch' in w[True], w[True]; print('promote-to-main.yml OK')"
```
Expected: `promote-to-main.yml OK`
(Note: PyYAML parses the YAML key `on:` as the boolean `True`; that is why the assertion reads `w[True]`.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/promote-to-main.yml
git commit -m "chore(ci): add manual testing-to-main promotion workflow"
```

---

## Task 5: Repoint the Docker `latest` tag to main

**Files:**
- Modify: `.github/workflows/docker.yml:24` (the `tags:` block of the `meta` step)

- [ ] **Step 1: Replace the `latest`-tag line**

In `.github/workflows/docker.yml`, the `docker/metadata-action` step has:

```yaml
          tags: |
            type=ref,event=tag
            type=raw,value=latest,enable={{is_default_branch}}
```

`{{is_default_branch}}` resolves against the GitHub default branch, which becomes `dev` (Task 7). `docker.yml` only triggers on `main` pushes and `v*` tags, so on a `main` push `{{is_default_branch}}` would be false and `latest` would never be applied. Replace that block with an explicit `main` check:

```yaml
          tags: |
            type=ref,event=tag
            type=raw,value=latest,enable=${{ github.ref == 'refs/heads/main' }}
```

- [ ] **Step 2: Verify the YAML parses**

Run:
```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/docker.yml')); print('docker.yml OK')"
```
Expected: `docker.yml OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/docker.yml
git commit -m "chore(ci): pin docker latest tag to main"
```

---

## Task 6: Update the qkt skill's branching strategy

**Files:**
- Modify: `.claude/skills/qkt/SKILL.md` (§2 — the `## 2. Branching strategy` section)

- [ ] **Step 1: Replace section 2**

In `.claude/skills/qkt/SKILL.md`, replace the entire `## 2. Branching strategy` section (from the `## 2. Branching strategy` heading down to, but not including, the next `---` separator) with:

```markdown
## 2. Branching strategy

qkt uses a three-branch promotion pipeline. Changes flow one way only:
`feature → dev → testing → main`.

- **`dev`** is the integration branch and the GitHub default branch. All work
  lands here first. It runs the fast essentials CI (compile + ktlint + unit
  tests).
- **`testing`** is the staging branch. It is updated *only* by automatic
  fast-forward promotion from `dev` once `dev` passes the essentials CI. It runs
  the full black-box integration CI.
- **`main`** is the release-ready branch. It is updated *only* by *manual*
  fast-forward promotion from `testing`, after `testing` passes the integration
  CI. Tags and releases are cut from `main`.

Never commit directly to `testing` or `main` — they are promotion-only. The
promotion is a fast-forward, so it creates no merge commit.

- `main`, `testing`, and `dev` must always build and test green.
- Feature branches branch from `dev` and merge back into `dev`. They are named
  after their phase and feature: `phase<N>-<short-feature-name>`. Examples:
  `phase2-event-bus`, `phase5-dsl-parser`.
- Bugfix branches: `fix-<short-description>` (no phase prefix; bugs aren't
  phase-scoped). Refactor branches: `refactor-<short-description>`.
- One concern per branch. Don't pile two features into one branch.
- Never commit directly to `dev`. Always go through a feature branch + PR.

The pipeline is specified in
`docs/superpowers/specs/2026-05-22-ci-promotion-pipeline-design.md`.
```

- [ ] **Step 2: Verify the section boundaries are intact**

Run:
```bash
grep -n '^## ' .claude/skills/qkt/SKILL.md | head -5
```
Expected: the section headings still read `## 0. ...`, `## 1. Phases`, `## 2. Branching strategy`, `## 3. Commit conventions`, … in order — confirming the replacement did not swallow an adjacent section.

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/qkt/SKILL.md
git commit -m "docs(skill): adopt three-branch promotion model in branching strategy"
```

---

## Task 7: Merge the feature branch and set up the branches

This task moves from the old single-`main` model to the new pipeline. Steps marked **[maintainer]** require a human with repo-admin rights and cannot be done by an agent.

- [ ] **Step 1: Merge `ci-promotion-pipeline` into `main`**

```bash
git checkout main
git pull --ff-only
git merge --no-ff ci-promotion-pipeline -m "merge: ci promotion pipeline"
git push origin main
```
This is the final merge under the old model. It triggers `integration.yml` on `main` and `docker.yml`; the integration run should pass because `tests/smoke-install.sh` was verified in Task 1.

- [ ] **Step 2: Watch the integration run on main**

```bash
gh run watch "$(gh run list --workflow integration.yml --branch main --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
```
Expected: the run concludes successfully. If it fails, fix `tests/smoke-install.sh` on a feature branch before continuing — the pipeline must start from a green `main`.

- [ ] **Step 3: Create `dev` and `testing` from `main`**

```bash
git checkout main && git pull --ff-only
git branch dev && git push origin dev
git branch testing && git push origin testing
```

- [ ] **Step 4: Set the GitHub default branch to `dev`**

```bash
gh repo edit elitekaycy/qkt --default-branch dev
```

- [ ] **Step 5: [maintainer] Create the `PROMOTE_TOKEN` secret**

In GitHub: **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**. Scope it to the `elitekaycy/qkt` repository only, with **Repository permissions → Contents: Read and write**. Copy the token, then:

```bash
gh secret set PROMOTE_TOKEN --repo elitekaycy/qkt
# paste the token when prompted
```
Without this secret the `promote-to-testing` and `promote-to-main` jobs fail at the checkout step.

- [ ] **Step 6: [maintainer] Add branch protection**

In GitHub **Settings → Rules → Rulesets**, create:
- A ruleset targeting `testing` and `main`: enable **Restrict deletions** and **Block force pushes**, and **Restrict updates** so only a bypass actor can push. Add the `PROMOTE_TOKEN` owner (your account) to the bypass list — the promotion workflows push as that identity.
- A ruleset targeting `dev`: **Require a pull request before merging** and **Require status checks to pass** with the `build` check selected.

This step is optional hardening — the pipeline functions without it, but without it nothing prevents a direct push to `testing`/`main`.

---

## Task 8: End-to-end pipeline verification

- [ ] **Step 1: Push a trivial change through a feature branch**

```bash
git checkout dev && git pull --ff-only
git checkout -b verify-pipeline
printf '\n<!-- pipeline verification %s -->\n' "$(date -u +%FT%TZ)" >> docs/backlog.md
git commit -am "chore: pipeline verification marker"
git push -u origin verify-pipeline
gh pr create --base dev --title "chore: pipeline verification" --body "Verifies the dev essentials gate."
```

- [ ] **Step 2: Confirm the essentials check ran on the PR**

```bash
gh pr checks verify-pipeline --watch
```
Expected: the `build` check runs and passes. The integration suite does **not** run on the PR.

- [ ] **Step 3: Merge the PR and confirm auto-promotion to testing**

```bash
gh pr merge verify-pipeline --merge --delete-branch
gh run watch "$(gh run list --workflow check.yml --branch dev --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
```
Expected: `check.yml` runs on `dev`; both `build` and `promote-to-testing` jobs succeed. Then:
```bash
git fetch origin && git log --oneline -1 origin/testing origin/dev
```
Expected: `origin/testing` now points at the same SHA as `origin/dev`.

- [ ] **Step 4: Confirm integration ran on testing**

```bash
gh run watch "$(gh run list --workflow integration.yml --branch testing --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
```
Expected: the integration run on `testing` succeeds. `main` is **not** updated.

- [ ] **Step 5: Manually promote testing to main**

```bash
gh workflow run promote-to-main.yml
gh run watch "$(gh run list --workflow promote-to-main.yml --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
git fetch origin && git log --oneline -1 origin/main origin/testing
```
Expected: the `promote-to-main` run succeeds; `origin/main` now points at the same SHA as `origin/testing`. The integration suite then runs once more on `main`:
```bash
gh run watch "$(gh run list --workflow integration.yml --branch main --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
```
Expected: success — the pipeline is proven end to end.

- [ ] **Step 6: Revert the verification marker**

```bash
git checkout dev && git pull --ff-only
git checkout -b revert-verification-marker
git revert --no-edit "$(git log --oneline --all --grep='pipeline verification marker' -1 --format=%H)"
git push -u origin revert-verification-marker
gh pr create --base dev --title "chore: revert pipeline verification marker" --body "Removes the marker added to verify the pipeline."
```
Then merge that PR — it will flow through the pipeline normally, confirming the steady state.

---

## Self-review notes

- **Spec coverage:** branch model → Tasks 3,6,7; essentials tier → Task 3; integration tier → Tasks 1,2; auto-promotion dev→testing → Task 3; manual promotion testing→main → Task 4; the PAT/token problem → Tasks 3,4,7; docker.yml repoint → Task 5; branch protection → Task 7; qkt skill update → Task 6; end-to-end proof → Task 8. All spec sections are covered.
- **Daemon stage caveat:** the daemon-lifecycle stage asserts control-plane behaviour (start, deploy, list, status, logs non-empty, stop) — not live trades, since CI has no market feed. This matches the spec's "Open risks" note.
- **CLI flags:** Task 1 Step 1 verifies `--state-dir`/`--as` against `qkt --help` before use; if they differ, the executor substitutes the real flags.
