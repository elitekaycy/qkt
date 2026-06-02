# qkt research REPL — interactive playback (v1) design

Issue: [#81](https://github.com/elitekaycy/qkt/issues/81) — *qkt research REPL: interactive strategy authoring with live tick replay against historical data.*
Status: design (brainstorm complete, pending plan).

---

## 1. Context and problem

Authoring a strategy today is an edit-file → `qkt backtest` → read-results loop. Every
iteration restarts a whole batch run and dumps a final report. There is no interactive
environment: you cannot pause inside a replay, watch trades appear in time order, jump to
the next fill, or re-run an edited strategy against an already-loaded data window without
paying the full reload each time.

`#81` proposes a `qkt research` REPL to close that loop. This spec covers **v1 only**: a
single interactive **playback session**. The two other modes the issue gestures at —
file-watch auto-rerun and an expression/indicator REPL — are deliberately deferred (see
§10) and become children of an `#81` epic.

## 2. Goals and non-goals

**Goals**
- `qkt research <strategy.qkt> --data <symbol> <from> <to>` opens a session that loads the
  strategy and the historical window once.
- Step / run / seek through a deterministic tick replay from a line-oriented prompt.
- Watch a live **event tape** (signals, orders, fills, rejections) plus a running footer
  (current timestamp, bar index, equity, PnL, open positions).
- Edit the `.qkt` in your own editor and `reload` to recompile + restart the replay
  against the same in-memory data.
- Replay results are **bit-identical** to `qkt backtest` over the same strategy + ticks.

**Non-goals (v1)**
- Per-bar rule introspection ("why did this rule fire", LET/condition values).
- In-session parameter overrides (`set ema_fast 12`) — the DSL has no parameter surface.
- Portfolio / multi-child `PORTFOLIO` files.
- Backward seek (`run-to <past time>` is implemented as reset-then-forward).
- Watch mode and the expression REPL.

## 3. Decisions (from the brainstorm)

| Decision | Choice | Rationale |
|---|---|---|
| v1 scope | Session core + Playback | The mode that literally satisfies "interactive + live tick replay" and forces the reusable core into existence; Watch + Expression REPL then layer onto it cheaply. |
| Live view | Event tape + running PnL/positions/equity | Pure reuse of existing pipeline callbacks; no new strategy-introspection surface. |
| UI medium | Line-oriented terminal REPL | Lowest cost, no new dependency, scrollback is history; matches a classic REPL. |
| Commands | run / step N / step \<dur\> / run-to \<time\> / run-to next-trade / reset / reload / show / quit | Navigation verbs matter when replaying weeks of data — jump to the interesting bars. |
| Tweak loop | Edit file + `reload` | The DSL has no named parameters (sweep varies strategies via a generic `(label, config) -> Backtest` factory). `set`-param would require inventing a `PARAM` surface — its own feature. |
| Replay driver | Shared replay core (reuse), `Backtest.run()` delegates | Accuracy by construction: one code path builds and advances the engine for both batch and interactive, so they cannot drift. Rejected: per-session copy of the loop (accuracy only test-hoped) and a concurrency-based pausable run (violates the single-threaded invariant). |
| `step N` unit | N bars on the primary (first-declared) stream's timeframe | More useful than raw ticks for a tape view; `step <dur>` covers finer control. |
| Data residency | Load the window into memory once; reset/reload replay from the cached tick list | Fast iteration; `reload` recompiles the strategy only, never re-reads the data. |

## 4. Architecture — one shared replay core

The accuracy guarantee lives in a single core that both the batch backtest and the
interactive session build and drive. Pacing only decides *when we stop pulling ticks*; the
tick→ingest order, clock advancement, and pipeline wiring are identical for both.

```
        ReplayEngine  (com.qkt.research)  — single source of truth
        ├─ owns: feed · clock · TradingPipeline · equity/PnL collectors
        ├─ advanceWhile(stop: (Tick) -> Boolean)
        ├─ advanceToEnd()
        └─ snapshot(): BacktestResult
                  ▲                              ▲
                  │ build + advance to end       │ build + interactive control
                  │                              │
        Backtest.run()                      ReplaySession  (com.qkt.research)
        = advanceToEnd() → snapshot()       = step / run-to / reset / reload / show
          (thin wrapper)
```

- **Construction is shared, not just the advance loop.** The same factory wires the
  pipeline + collectors for batch and interactive, so there is no second place for the
  wiring to drift.
- New package `com.qkt.research`. `Backtest` keeps its public API; `run()` reduces to
  *build engine → advance to end → snapshot*. This is a behavior-preserving extraction,
  proven by `Backtest`'s existing test suite staying green unchanged.
- Per-tick advance is exactly `clock.time = tick.timestamp; pipeline.ingest(tick)` —
  `pipeline.ingest` already fans out to the engine, candle hub, and schedule runner.

### Snapshot semantics
`snapshot()` builds a `BacktestResult` from the collectors *as of the current tick*: realized
PnL from closed trades plus unrealized PnL on open positions valued at the last seen price.
A mid-replay snapshot does not force-close positions. At end-of-feed the snapshot equals
`Backtest.run()`'s result. Any end-of-run finalization step `Backtest` performs today is
owned by the shared core so both paths apply it identically.

## 5. Components

| Component | Package | Responsibility |
|---|---|---|
| `ReplayEngine` | `research` | Owns feed + clock + pipeline + collectors. `advanceWhile(stop)`, `advanceToEnd()`, `snapshot()`. |
| `ReplaySession` | `research` | Holds the cached tick list, current `ReplayEngine`, and the source path. Maps commands → stop predicates; `reset()` rebuilds the engine from the cached ticks; `reload()` re-parses/recompiles the file then `reset()`. Buffers events emitted since the last prompt. |
| `ReplayCommand` | `research` | Sealed type + pure parser: `"step 2d"` → `Step(Duration)`, `"run-to next-trade"` → `RunToNextTrade`, etc. Unit-testable with no IO. |
| `TapeRenderer` | `research` | Formats tape lines (signal / fill / rejection) and the status footer (timestamp, bar index, equity, PnL, open positions). |
| `ResearchCommand` | `cli` | Arg parsing (reuses `BacktestCommand`'s data resolution + Phase-6 auto-fetch), builds the session, runs the read-eval-print loop over an injectable `Reader`/`Writer`, dispatches to the session, renders via `TapeRenderer`. One new case in `Main.kt`. |

## 6. Command surface and session lifecycle

States: **Loaded** (nothing replayed yet) → **Mid-replay** (paused at a timestamp) →
**Ended** (feed exhausted). `reset` / `reload` return to Loaded.

| Command | Effect |
|---|---|
| `run` | Advance to end of feed. |
| `step N` | Advance N bars (closes on the primary stream's timeframe). |
| `step <dur>` | Advance a duration: `1h`, `1d`, `30m`. |
| `run-to <time>` | Advance to a timestamp. If in the past, prints a notice and does reset-then-forward. |
| `run-to next-trade` | Advance until the next fill. |
| `reset` | Replay from start, same strategy. |
| `reload` | Re-read + recompile the `.qkt`. On failure, keep the current strategy and print diagnostics. On success, `reset`. |
| `show` | Print the current position / PnL / equity snapshot without advancing. |
| `quit` / EOF | Clean exit. |

`step`, `run-to`, and `run-to next-trade` differ only in the stop predicate passed to
`advanceWhile`.

## 7. Data flow

```
stdin line
  → ReplayCommand.parse
  → ReplaySession dispatch
  → ReplayEngine.advanceWhile(stop)
       per pulled tick: clock.time = ts; pipeline.ingest(tick)
       pipeline callbacks (onFilled / onRejected / onCandle) append to the event buffer
         and update the equity / PnL collectors
  → on return: TapeRenderer prints buffered events + status footer
  → prompt again
```

## 8. Accuracy / determinism (load-bearing invariant)

**Any sequence of `step` / `run-to` that reaches end-of-feed yields a `snapshot()`
bit-identical to `Backtest.run()` over the same ticks + compiled strategy.** Because both
paths share one construction + advance code path, divergence is impossible by
construction; the invariant test (real ticks, real compiled strategy, no mocks) guards
against regressions in the shared core.

## 9. Error handling

- Parse/compile error on initial load → print `ParseError` diagnostics (line/col/message,
  reusing the formatting from the editor-tooling path) and exit non-zero.
- Parse/compile error on `reload` → keep the current strategy, print diagnostics, stay at
  the prompt.
- Missing data → reuse the backtest fetch path; if still unavailable, a clear error.
- Unknown command or bad args → one-line usage hint; the loop never crashes.
- `run-to <past time>` → notice + reset-and-forward.

## 10. Testing

- `ReplayEngine` / `ReplaySession`: fixed `List<Tick>` + a compiled strategy; assert
  arbitrary stepping increments to end == `Backtest.run()` (the §8 invariant); `reset`
  restores initial state; `reload` picks up edited source.
- `ReplayCommand`: pure string → command unit tests.
- One thin integration test: drive a scripted command sequence through the session via an
  injected `Reader`, assert rendered output.
- `Backtest`'s existing suite must stay green unchanged — proves the extraction preserved
  behavior.

## 11. Out of scope → `#81` epic children

`#81` becomes the epic. Children, each its own spec → plan → PR, all reusing the v1 core:

- **Watch mode** — a file-watcher triggers `reset` + `run` on the same `ReplayEngine`,
  rendering a compact final report on every save.
- **Expression REPL** — expression-level DSL evaluation against the loaded data + the
  rule-introspection view (`eval ema(9)`, per-bar condition/LET values).
- **DSL parameters** — a `PARAM` surface that unlocks in-session `set` overrides and
  cleaner sweep ergonomics.

## 12. Risks

- **Extraction blast radius.** Reducing `Backtest.run()` to a wrapper touches a core,
  well-tested class. Mitigation: behavior-preserving refactor; existing tests are the
  guardrail and must pass unchanged before any new behavior is added.
- **Bar boundary for multi-stream strategies.** `step N` keys off the first-declared
  stream's timeframe. Documented; revisit if multi-timeframe authoring finds it confusing.
- **Memory for large windows.** Loading a window into memory once trades RAM for
  re-run speed. FX/commodity tick volumes for a month are well within budget; document the
  ceiling if a user loads a year of ticks.

## 13. References

- Issue: [#81](https://github.com/elitekaycy/qkt/issues/81)
- Reused surfaces: `Dsl.parse` + `AstCompiler.compile` (DSL → `Strategy`),
  `Backtest` (`com.qkt.backtest`), `TradingPipeline.ingest` (`com.qkt.app`),
  `LocalMarketSource.ticks` + `DefaultDataStore` (`com.qkt.marketdata`),
  `BacktestResult` / `PerformanceReport` (`com.qkt.backtest`).
- Plan: _to be written (`docs/superpowers/plans/2026-06-02-issue81-research-repl.md`)._
