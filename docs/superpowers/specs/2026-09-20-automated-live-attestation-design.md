# Automated full-coverage live attestation

Status: design. Tracks the move from a hand-run four-case smoke wave to an unattended,
time-boxed attestation that covers the whole DSL on every promotion to `main`.

## Why

Promotion to `main` requires a live-parity attestation. Today that is a four-case wave
(EMA, RSI, ATR, CASE on EURUSD/GBPUSD, one market bracket each) that takes about an hour,
is started by hand, and whose bundle is assembled by hand. It proves the order path and
four indicators. It does not prove the other indicators, the order types, the risk
paths or a portfolio at a real venue, and it cannot run on a machine nobody is watching.

## Goals

1. **Coverage.** Every capability in `src/test/resources/validation/oracle-evidence.json`
   and every order shape, risk path and lifecycle the DSL supports is exercised live and
   compared with replay, on every promotion. A capability is `covered`, `not-applicable`
   with a reason, or an explicit `gap` that fails the run.
2. **Ten minutes.** Wall-clock from start to verdict is at most ten minutes on a quoting
   market. No case waits on another unless they contend for the same venue state.
3. **Deterministic.** The same commit and seed generate byte-identical strategies,
   configs and expectations. Only the market input differs between runs, and it is
   captured, fingerprinted and replayed.
4. **Unattended.** One command, no prompts, machine-readable result, non-zero exit on any
   failure. Safe to run from a timer or a self-hosted runner on a VPS.
5. **Not limiting.** The catalog describes *what must be proven*; it does not constrain
   what strategies users write. Adding a DSL feature means adding a catalog entry, not
   editing a runner.

## How ten minutes is possible

The hour is spent waiting, serially. Nothing in the wave is CPU-bound.

| Today | Cost | Replacement |
|---|---|---|
| Four read-only captures, one after another | 4 x 5.5 min | One shared capture: every shadow strategy loads into the same daemon and sees the same ticks |
| Four armed cases, one after another, because each asserts the *account* is flat and the *account* balance moved by its deals | 4 x 6 min | Lanes keyed by magic number: flat means "no position under my magic", reconciliation means "my deals net to my ledger". Lanes then run concurrently on one account |
| 5m bars to prove a second timeframe | 5 min floor | Higher timeframes come from gateway history warmup plus a 1m window that crosses the boundary, as `live-parity-attestation.md` already prescribes |
| JVM start per step | ~1 min each | One daemon per lane for the whole window |

### The window

```
t=0      preflight: build identity, image digest, account identity, runner lock
t=0:30   start lanes (all concurrent)
           shadow lane     1 daemon, N read-only strategies, every indicator/math/DSL expr, LOG traces
           order lanes     k daemons, one magic each: market, limit, stop, bracket/OCO, trailing,
                           stack, OTO, GTD+cancel, partial close, risk-sized entry
           risk lane       zero-mutation rejections + stateful halts (restored state)
           book lane       one two-child portfolio with a book cap
           insights lane   collector attached to one order lane
t=6:30   stop lanes, seal captures
t=6:30   replay every lane offline, in parallel (full-ticks-paper, full-ticks-mt5, bars-paper)
t=8:30   compare, assemble, verify
t=9:00   verdict; dispatch paper-soak if requested
```

A lane that has not resolved by its deadline fails; it is never waited on.

## Determinism

- `catalog/*.yaml` is the source of truth: one entry per capability with the expression,
  the symbols and timeframes it needs, and the lane it runs in.
- `generate` turns catalog + commit SHA + seed into a suite directory: `.qkt` files,
  configs, `expected.json`, magic allocation. It makes no network call. Its output is
  hashed into `inputFingerprint` together with the captured ticks.
- Order cases never depend on market direction. Entries are unconditional on the first
  evaluated bar; exits are timed. A case whose premise needs a loss (loss-streak halt)
  restores that state instead of hoping the market supplies it. (The gold
  `reentry_blocked_loss_streak` step failed on 2026-09-20 for exactly this reason: the
  first trade won.)
- Comparison is value-for-value on the trace: every logged indicator, math result and DSL
  boolean, every order request, every protective level. Fill *prices* are compared within
  the reviewed drift for the symbol's own point.

## Unattended operation

```
scripts/live-validation/run-attestation.sh --profile <file> [--dispatch]
```

- `--profile` holds the gateway URL, expected account identity, output roots and image
  repository. Secrets come from the environment only.
- Writes `attestation-run.json` (status, stage, timings, artifact paths) at every stage so
  a dashboard or a second process can follow it.
- Takes the account lock once for the whole window; refuses to start if it is held.
- Exit 0 only when the bundle passes `verify-paper-soak-attestation.py`.
- On the VPS: a self-hosted runner job triggered by a push to `testing` runs it, uploads
  the bundle, dispatches `paper-soak`, and `promote-to-main` opens the PR. A failed run
  posts the failing lane and stage; nothing is promoted.

## Phases

1. **Foundation (this PR).** `assemble-attestation.py` in the repo (it was a scratch
   script, lost once, and the bundle was otherwise hand-assembled). `run-attestation.sh`
   wrapping today's wave end to end with no prompts. No change to what is proven.
2. **Lanes by magic.** Per-magic flat check and per-magic deal reconciliation in
   `run-market-bracket.sh`; concurrent order lanes. Target: current coverage in 12 min.
3. **Shared shadow capture.** One daemon, generated read-only strategies for all 42
   catalog indicators and the numeric functions. Target: full indicator coverage, no
   extra wall-clock.
4. **Order-shape, risk and book lanes** generated from the catalog.
5. **VPS.** Runner job on `testing` push, automatic dispatch, status page.

## Open questions

- One demo account is enough for concurrency by magic on a hedging account. A netting
  account would need one account per order lane.
- Quiet markets: the window needs ticks. Outside liquid hours the run should fail fast
  with `market-quiet`, not stretch.
