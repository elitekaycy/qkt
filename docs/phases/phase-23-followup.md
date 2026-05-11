# Phase 23 follow-up — DSL corrections and packaging

## Summary

Two threads land together. The first closes four parser-vs-docs gaps discovered while building an end-to-end smoke test: snippets from the cookbook that the parser silently rejected. The second adds an installation path — a one-line shell installer that fetches the latest release tarball — plus a release workflow that produces the tarball on every `v*` tag, plus a CI-gated smoke test that exercises the full install → parse → backtest → Docker pipeline on every PR.

The DSL corrections are pure bug fixes: every change makes documented syntax work. The packaging additions are pure additions: nothing previously worked differently.

## What's new

### DSL

- **Double-quoted strings.** `LOG "hello"` parses. Until now the lexer accepted only single quotes (`'hello'`), but every cookbook snippet uses double quotes. Both forms are interchangeable. Escapes (`\"`, `\\`, `\n`, `\t`) work in either delimiter.
- **`=` as equality in WHEN conditions.** `WHEN POSITION.btc = 0 THEN ...` parses. The parser previously accepted only `==`; the docs uniformly used `=`. Both forms now mean equality. There is no grammatical ambiguity — `=` never had another meaning in expression position.
- **Multi-action rules separated by `;`.** `THEN BUY btc SIZING 0.01 ; LOG "long"` parses to a `Block(actions=[Buy, Log])` that fires both actions on the same trigger. Previously every `THEN` accepted exactly one action. Newline-as-separator is **not** supported — only `;`.
- **Case-insensitive indicator names.** `ema(btc.close, 9)` works in addition to `EMA(...)`. The DSL is otherwise case-insensitive for keywords; indicators were the outlier.

### CLI

- **`qkt --help` lists every subcommand.** The help text previously documented only `parse`, `backtest`, `run` — nine of the twelve subcommands were missing. The output now groups by purpose: strategy authoring, daemon lifecycle, daemon operations, venue/feed.

### Packaging

- **`scripts/install.sh`** — one-line curl-pipe installer:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.sh | bash
  ```
  Fetches the latest GitHub release tarball, extracts to `~/.local/share/qkt`, symlinks `~/.local/bin/qkt`. Honors `QKT_VERSION`, `QKT_PREFIX`, `QKT_BIN_DIR`, `QKT_REPO` env vars. Verifies JDK 21 is present and prints PATH advice if needed.
- **`.github/workflows/release.yml`** — runs `./gradlew distTar` on every `v*` tag push, verifies the tarball contains `bin/qkt`, attaches to the existing release (or creates a draft if missing).
- **`tests/smoke-install.sh`** — end-to-end smoke. Builds the distribution, simulates a clean install in a temp directory, parses + backtests a tiny strategy, runs a real momentum strategy with `--json` validation, optionally builds the Docker image and runs the same backtest inside the container. Used both locally (`bash tests/smoke-install.sh`) and in CI.
- **CI smoke job.** `.github/workflows/check.yml` runs the smoke script on every push to `main` and every PR. Catches packaging regressions (Dockerfile drift, install layout) that unit tests can't see.

## Migration from previous phase

The DSL fixes are pure additions — no existing strategy is invalidated.

| Before | After |
| --- | --- |
| `LOG 'long'` (still works) | `LOG "long"` (also works now) |
| `POSITION.btc == 0` (still works) | `POSITION.btc = 0` (also works now) |
| `THEN BUY btc` (one action) | `THEN BUY btc ; LOG "long"` (multi-action) |
| `EMA(btc.close, 9)` (still works) | `ema(btc.close, 9)` (also works now) |

## Usage cookbook

### Author a strategy that takes a trade and logs it

```qkt
STRATEGY momentum VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSD EVERY 1m

RULES
    WHEN ema(btc.close, 5) CROSSES ABOVE ema(btc.close, 13)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.01 ; LOG "long {p}" p=btc.close

    WHEN ema(btc.close, 5) CROSSES BELOW ema(btc.close, 13)
     AND POSITION.btc > 0
    THEN CLOSE btc ; LOG "exit {p}" p=btc.close
```

Three of the four DSL changes show up: lowercase `ema`, `=` for equality, `;` between actions.

### Install qkt on a fresh box

```bash
curl -fsSL https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.sh | bash
# … or pin a version:
QKT_VERSION=v0.25.0 curl -fsSL https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.sh | bash
```

Then verify:

```bash
qkt --version       # qkt 0.25.0
qkt --help
qkt brokers list
```

### Run the smoke locally before pushing

```bash
bash tests/smoke-install.sh              # full, incl. Docker
bash tests/smoke-install.sh --no-docker  # skip the Docker stage
```

On failure the script preserves the temp directory and prints the log path. On success it cleans up.

## Testing patterns

- **Parse-level regressions** are caught by `LexerTest` and `ParserActionTest`. New cases: double-quoted strings (`tokenizes double-quoted strings`, `double-quoted strings support escapes`); multi-action `Block` (`multi-action rule with semicolon separator parses as Block`).
- **Compiler-level regressions** are caught by the full DSL test suite, which now runs the same `mergeDefaults` / `IterVarSubstitution` paths for `Block` actions as for single actions.
- **Packaging regressions** are caught by `tests/smoke-install.sh` running in CI. The script intentionally exercises an installed `qkt` binary, not a Gradle invocation, so it would catch a broken distribution layout.

## Known limitations

- **Multi-action with newline separator is not supported.** Only `;`. Adding newline-as-separator would require a lexer change to emit a `NEWLINE` token; deferred.
- **The smoke test's `qkt run` step is gated behind `QKT_SMOKE_RUN=1`** and skipped by default. The `run` subcommand needs a live tick feed (TradingView), which is unreliable in CI. An offline tick source would let this run unconditionally.
- **`scripts/install.sh` requires JDK 21 to be already installed.** Bundling a JDK would balloon the tarball; we expect operators to install Java themselves.

## References

- DSL changes: lexer/parser/compiler under `src/main/kotlin/com/qkt/dsl/`.
- Installer: `scripts/install.sh`.
- Release workflow: `.github/workflows/release.yml`.
- Smoke test: `tests/smoke-install.sh`.
- CI smoke job: `.github/workflows/check.yml`.
