# Daemon resync implementation plan

## Goal

Implement `qkt resync` as a safe daemon lifecycle command for replacing an
already deployed strategy or portfolio artifact, with documentation and template
support. The command must fit qkt's event-driven architecture: no in-place hot
reload, no hot-path work, and no bypass of deploy-time reconciliation or
production gates.

## Spec

`docs/superpowers/specs/2026-07-07-daemon-resync-design.md`

## Branch

`feat-daemon-resync`

## Task 1: resync command surface

- [x] Add `ResyncCommand` under `com.qkt.cli`.
- [x] Support `qkt resync <file.qkt> --as <name>`.
- [x] Support `--dry-run`, `--json`, `--state-dir`, `--reconcile=ignore-mismatches`.
- [x] Route through `ControlClient`.
- [x] Add command to `Main.kt` help and dispatch.
- [x] Add focused command tests.

## Task 2: control client and HTTP route

- [x] Add `ControlClient.resync(...)`.
- [x] Add `POST /resync` to `ControlRoutes`.
- [x] Request body: `{"file":"...","name":"...","dryRun":true|false}`.
- [x] Query parameters reuse deploy's reconcile and waiver policy.
- [x] Response includes `name`, `kind`, `state`, `dryRun`, and `affected`.
- [x] Return `404` for unknown target, `400` for bad file/parse, `409` for reconcile or conflict.

## Task 3: registry replacement

- [x] Add `StrategyRegistry.resync(...)` for standalone strategy replacement.
- [x] Add dry-run planning that parses/classifies without mutation.
- [x] Implement apply by halting old new entries, creating the replacement
      handle, atomically swapping it into the registry, and closing the old
      handle after the new one starts.
- [x] Preserve unrelated strategy handles.
- [x] Record operator journal action `resync`.
- [x] Keep `deploy` first-start only.

## Task 4: portfolio support

- [x] Whole-portfolio resync: replace a deployed portfolio when the replacement
      file parses as a portfolio.
- [x] Fail closed if a strategy file is supplied for a portfolio target or a
      portfolio file is supplied for a strategy target.
- [x] Document child-level resync as planned if it is not implemented in this
      PR.

## Task 5: templates

- [x] Update deploy-oriented template Makefiles with `resync`,
      `resync-dry-run`, and `reconcile` targets.
- [x] Add conservative README guidance where templates already carry deployment
      workflow files; no new `deploy.yaml` is introduced because no code
      consumes it.
- [x] Extend template tests to assert resync targets exist in mt5, mt5-ci,
      portfolio, and bybit/minimal where applicable.

## Task 6: docs

- [x] Update `docs/reference/cli-commands.md`.
- [x] Update `docs/get-started/quickstart.md`.
- [x] Update `docs/get-started/deploy-paper.md`.
- [x] Update `docs/get-started/deploy-mt5.md`.
- [x] Update `docs/tutorials/backtest-to-live.md`.
- [x] Update `docs/operations/deploy.md` and troubleshooting with failure modes.

## Task 7: validation

- [x] Run targeted CLI/control-plane tests.
- [x] Run template tests.
- [x] Run ktlint check or format/check for touched Kotlin.
- [x] Run a focused Gradle test set with Kotlin daemon strategy if needed.
- [x] Audit the implementation against the spec acceptance list before PR.

## Acceptance

- CLI command works through the daemon control plane.
- Dry-run does not mutate a running registry.
- Successful standalone resync replaces only the target strategy.
- Failed parse/reconcile leaves the old target active.
- Docs and templates teach deploy plus resync as the normal lifecycle.
- Tests prove the behavior above with current code, not just mocked intent.
