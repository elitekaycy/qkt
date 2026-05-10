# Phase 14 — Portfolio v2: Daemon Fan-Out + Per-Child Observability

**Phase:** 14
**Status:** Design
**Author:** elitekaycy
**Date:** 2026-05-10

---

## 1. Goal

Make `.qkt` portfolio files first-class citizens of the daemon. Closes the largest known limitation from Phase 13b: portfolios run end-to-end via the `Backtest` API, but the daemon does not understand portfolio files (`qkt deploy mybook.qkt` against a `PORTFOLIO ...` file is undefined behavior).

Phase 14 ships:

1. Daemon understands portfolio files: `qkt deploy/list/status/stop/logs` work for portfolios and their children.
2. Per-child observability: each child runs in its own `LiveSession` with its own broker, positions, ports, and log file. `qkt status mybook` aggregates; `qkt status mybook/trend` drills in.
3. New verb `qkt start <portfolio>/<child>` for clearing operator-stop on a single child.

Out of scope (deferred):

- `WEIGHT` clause for capital allocation.
- Import-time overrides on a child (`IMPORT 'a.qkt' AS a OVERRIDE { ... }`).
- Nested portfolios.
- `qkt reload <portfolio>` without stop.

The Phase 13b `PortfolioStrategy` continues to power the `Backtest` API path unchanged. Phase 14 introduces a parallel daemon-side runtime: `PortfolioSupervisor` + child fan-out.

---

## 2. Why fan-out

The Phase 13b `PortfolioStrategy` is a single `DslCompiledStrategy` that wraps N children and dispatches to whichever the gate selects. That works for backtests (single position tracker is fine) but it forces three problems on the daemon:

- One `LiveSession` for the whole portfolio means one log file. Per-child log filtering would need a new tagging scheme.
- One `BrokerExecutor` and one `StrategyContext` mean one `PositionTracker`, so per-child PnL has nowhere to live.
- A single `ObservabilityServer` exposes one `/status` for the whole portfolio. Per-child drill-down forces a new sub-protocol.

Fanning out — one `LiveSession` per child + a thin `PortfolioSupervisor` above them — eliminates all three:

- Each child gets its own log file via the existing logback `SiftingAppender` keyed on `MDC.strategy`.
- Each child gets its own broker, positions, and PnL for free.
- Each child gets its own observability port and `/status`. Daemon control-plane composes the portfolio-level view.

Cost: a new supervisor object, new gate-on-pipeline mechanism, new cascade-stop logic, new `qkt start` verb. All additive — no regression risk on the strategy or backtest paths.

---

## 3. Architecture

### 3.1 Naming

Three kinds of registry entries:

| Kind | Name format | Examples |
|---|---|---|
| `strategy` | `[A-Za-z0-9_-]+` | `rsi-1m`, `momentum_basket` |
| `portfolio` | `[A-Za-z0-9_-]+` | `mybook`, `prod_v3` |
| `child` | `<portfolio>/<alias>` | `mybook/trend`, `mybook/range` |

Top-level entries (`strategy` and `portfolio`) cannot contain `/`. Children are created exclusively via portfolio deploy; `qkt deploy mybook/trend.qkt` has no portfolio context and is rejected.

The `StrategyRegistry` regex widens to permit one optional `/<alias>` suffix:

```kotlin
private val NAME_REGEX = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)?")
```

`POST /deploy` only accepts top-level names — no slashes — and rejects with 400 otherwise.

### 3.2 Registry record types

`StrategyRegistry` holds `RegistryEntry`, a sealed type:

```kotlin
sealed class RegistryEntry {
    abstract val name: String
    abstract val startedAt: Instant

    data class Strategy(
        override val name: String,
        override val startedAt: Instant,
        val handle: StrategyHandle,
    ) : RegistryEntry()

    data class Portfolio(
        override val name: String,
        override val startedAt: Instant,
        val supervisor: PortfolioSupervisor,
        val childAliases: List<String>,
        val logFile: Path,
    ) : RegistryEntry()

    data class Child(
        override val name: String,                 // "<portfolio>/<alias>"
        override val startedAt: Instant,
        val parent: String,
        val alias: String,
        val hold: Boolean,
        val handle: StrategyHandle,
        val gateActive: AtomicBoolean,
        val operatorStop: AtomicBoolean,
    ) : RegistryEntry()
}
```

`StrategyRegistry` exposes `list(): List<RegistryEntry>`, `get(name): RegistryEntry?`, and predicates (`isStrategy`, `isPortfolio`, `isChild`). Deploy and stop dispatch on `RegistryEntry` kind.

### 3.3 PortfolioSupervisor

New class under `com.qkt.cli.daemon.portfolio.PortfolioSupervisor`.

Responsibilities:

- Subscribe to a `MarketSource` for the portfolio's `SYMBOLS` block (one subscription, independent of any child's subscription).
- On each candle (in supervisor's own coroutine / thread), evaluate every `PortfolioRule` against current candle context using the existing `ExprCompiler` + `EvalContext`.
- Compute desired-active map: for each child alias, `desired = ruleResults.any { it targets this alias }`.
- Diff against current `gateActive` flags. For each transition:
  - `false → true` (activate): set `gateActive = true`. Log `"<alias> activated by rule <i>"`.
  - `true → false` (deactivate): set `gateActive = false`. Log `"<alias> deactivated, hold=<bool>"`. If `hold == false`, call `child.flatten()`, which emits `CancelPendingForSymbol` plus a close-position signal for every non-zero position via the child's own `TradingPipeline` → child's broker → child's PnL realizes.

Supervisor does NOT have its own broker, position tracker, or `ObservabilityServer`. Its `/status` view is composed by the daemon control plane (Section 3.6).

Supervisor lifecycle:

- `start()`: open market subscription, start tick loop. Idempotent.
- `stop()`: close market subscription, halt tick loop, blocks until the loop's current tick completes (must not race with `child.flatten()` on cascade-stop).
- `running: Boolean`: true between `start()` and `stop()`.

### 3.4 Child gating

`ChildHandle` is a thin wrapper around `StrategyHandle`. Gate enforcement lives at the top of the child's `TradingPipeline.onCandle`:

```kotlin
class TradingPipeline(
    /* existing fields */,
    private val gate: () -> Boolean = { true },   // new
) {
    fun onCandle(candle: Candle) {
        if (!gate()) return                        // gated: skip the whole tick
        /* existing dispatch path */
    }
}
```

The `gate` lambda for a child evaluates `gateActive.get() && !operatorStop.get()`. For top-level strategies, the lambda defaults to `{ true }` — no behavior change.

**Indicator warming under gate.** Skipping the whole `onCandle` means the child's strategy does not see candles while gated; indicators do not advance. This matches 13b's `PortfolioStrategy` semantics exactly (the gate decides whether to call the child's `onCandle` at all). It is a real concern — a child gated off for an hour will have stale indicator state on activation — but it is not a v2 regression. Documented in Section 6 as a known limitation; future work may move the gate downstream of indicator warming.

Child `flatten()` is implemented on the child's `LiveSession`:

```kotlin
fun flatten() {
    val positions = strategyContext.positions.allPositions().filterValues { it.qty != BigDecimal.ZERO }
    for ((symbol, pos) in positions) {
        signalBus.emit(Signal.CancelPendingForSymbol(symbol))
        val side = if (pos.qty > BigDecimal.ZERO) Signal.Sell else Signal.Buy
        signalBus.emit(side(symbol, pos.qty.abs()))
    }
}
```

Flatten runs synchronously on the supervisor's thread and writes to the child's signal bus. The child's pipeline drains the bus on its next tick — but the child's gate is now `false`, so no new signals will be emitted. The flatten signals themselves bypass the gate (they originate from outside the rule engine).

### 3.5 Deploy flow

`POST /deploy` body (unchanged): `{"file": "<absolute path>", "name": "<top-level name>"}`.

```
1. parsed = Dsl.parseFileAny(file)
2. when parsed:
     ParsedFile.StrategyFile -> deployStrategy(name, parsed.ast)
     ParsedFile.PortfolioFile -> deployPortfolio(name, file, parsed.ast)
```

`deployStrategy` is today's path, untouched.

`deployPortfolio(name, file, ast)`:

```
1. compiled = PortfolioLoader().load(file)            // 13b loader, unchanged
2. preflight conflict check:
     - reject if name in registry
     - reject if any "<name>/<alias>" already in registry
3. for each compiledChild in compiled.children:
     a. fullName = "$name/${compiledChild.alias}"
     b. session = LiveSession(...)
                  with strategy = compiledChild.compiled
                  with mdc("strategy" -> fullName, "parent" -> name)
                  with gate = childHandle.effectiveActive
     c. server = ObservabilityServer(...)
     d. logFile = stateDir.logFile(fullName)
     e. childHandle = ChildHandle(
            name=fullName, parent=name, alias=compiledChild.alias,
            hold=compiledChild.hold,
            gateActive=AtomicBoolean(false),
            operatorStop=AtomicBoolean(false),
            session, server, logFile, ...
        )
     f. registry.put(childHandle)
4. supervisor = PortfolioSupervisor(
       portfolioAst = ast,
       marketSource = if (ast.streams.isEmpty()) null else marketSourceProvider(ast.streams.tvSymbols),
       children = childHandles,
       logFile = stateDir.logFile(name),
   )
   // ast.streams empty => portfolio has no SYMBOLS block; supervisor evaluates AlwaysRun
   // rules once at start (children with AlwaysRun activate immediately, no ticking needed).
   // A WhenRun whose condition references a stream not declared in the portfolio's SYMBOLS
   // block is a compile-time error (ExprCompiler fails to resolve the stream); v2 surfaces
   // that as a 400 from /deploy with the compiler's error message.
5. supervisor.start()
6. portfolioRecord = PortfolioRecord(name, supervisor, childAliases, logFile, startedAt)
7. registry.put(portfolioRecord)
8. respond { name, kind: "portfolio", state: "running", startedAt, children: [...] }
```

**Rollback on partial failure:** if any step after step 3 fails (e.g., supervisor `start()` throws), every child session and observability server already created in step 3 must be `close()`d before the deploy returns 500. No half-started portfolio state in the registry.

### 3.6 HTTP routes

Additive changes to `ControlRoutes`:

| Method | Path | Behavior change |
|---|---|---|
| `POST` | `/deploy` | Body unchanged. Dispatches by `ParsedFile` kind. Response includes `kind` field |
| `GET` | `/list` | Each entry gains `kind` (`strategy`/`portfolio`/`child`) and `parent` (children only) |
| `GET` | `/status/<name>` | Strategy: as today. Child: existing snapshot + `gateActive`, `operatorStop`, `hold`, `parent`. Portfolio: aggregated view (Section 3.7) |
| `GET` | `/logs/<name>` | Portfolio → supervisor's log. Child → child's log. Strategy unchanged. Slash in `<name>` survives existing `removePrefix` logic |
| `POST` | `/stop/<name>` | Strategy: as today. Portfolio: cascade (Section 3.8). Child: operator-stop + flatten-if-no-hold |
| `POST` | `/start/<name>` | **New.** Child: `operatorStop = false`, returns 200. Portfolio: 400 ("portfolio cannot be paused; use deploy"). Strategy: 400 |

URL parsing: today's `path.removePrefix("/status/").trim('/')` already accepts `mybook/trend`. The string is used as-is for registry lookup; no change needed in routes.

### 3.7 Portfolio-level `/status` shape

`GET /status/mybook` composes a snapshot from the supervisor + every child:

```json
{
  "name": "mybook",
  "kind": "portfolio",
  "version": 1,
  "startedAt": "2026-05-10T...",
  "uptimeMs": 123456,
  "supervisorRunning": true,
  "equity": "10042.13",
  "balance": "10000.00",
  "realized": "42.13",
  "unrealized": "0.00",
  "children": [
    {
      "alias": "trend",
      "name": "mybook/trend",
      "port": 54211,
      "gateActive": true,
      "operatorStop": false,
      "hold": false,
      "trades": 28,
      "realized": "42.13",
      "unrealized": "0.00"
    },
    {
      "alias": "range",
      "name": "mybook/range",
      "port": 54212,
      "gateActive": false,
      "operatorStop": false,
      "hold": true,
      "trades": 14,
      "realized": "0.00",
      "unrealized": "0.00"
    }
  ]
}
```

Aggregates (`equity`, `balance`, `realized`, `unrealized`) are sums across children. The control plane fetches each child's `/status` from its observability port and folds.

### 3.8 Cascade stop

`POST /stop/mybook`:

```
1. record = registry.get("mybook") as Portfolio || 404
2. supervisor.stop()                              // halt rule eval; blocks
3. for child in registry.children(parent="mybook"):
     a. if !child.hold:
          child.handle.flatten()                  // emit close signals
          await child pipeline drains             // bounded wait, e.g. 5s
     b. child.handle.close()                      // stop session + observability
     c. registry.remove(child.name)
4. registry.remove("mybook")
5. respond { name: "mybook", state: "stopped", trades: <sum> }
```

Ordering matters: supervisor must stop before flatten, otherwise the supervisor's next tick could re-evaluate the gate to `true` and emit signals during flatten. The supervisor's `stop()` is synchronous and blocks until any in-flight tick completes.

`POST /stop/mybook/trend`:

```
1. child = registry.get("mybook/trend") as Child || 404
2. child.operatorStop.set(true)
3. child.gateActive.set(false)                    // immediate effect
4. if !child.hold: child.handle.flatten()
5. respond { name: "mybook/trend", state: "operator_stopped", trades: <count> }
```

Operator-stopped child stays registered. The pipeline keeps polling its market subscription, but the gate skips `onCandle` so no rule eval, no indicator updates, no signals — same behavior as a child the supervisor has deactivated. To re-enable: `POST /start/mybook/trend`.

### 3.9 Logging

Children: MDC keys set per child, every signal/trade/log line in the child's `LiveSession`:

```
MDC.strategy = "mybook/trend"
MDC.parent   = "mybook"
```

Logback `SiftingAppender` already keys on `strategy`. Filesystem-safe filename: replace `/` with `-`, so `mybook-trend.log`. The state-dir helper (`StateDir.logFile(name)`) handles the substitution centrally.

Supervisor: `MDC.strategy = "mybook"`, no `parent`. Logs gate transitions at `INFO`:

```
2026-05-10 10:24:01.123 INFO  [mybook] trend activated by rule 0 (close > 100)
2026-05-10 10:24:31.456 INFO  [mybook] range deactivated, hold=true
2026-05-10 10:25:02.789 INFO  [mybook] trend deactivated, hold=false, flattening positions
```

### 3.10 Concurrency model

Each child runs on its own `LiveSession` thread (existing model — one thread per session). The supervisor runs on its own thread, polling its market source.

Shared mutable state across threads:

- `child.gateActive: AtomicBoolean` — supervisor writes, child reads.
- `child.operatorStop: AtomicBoolean` — control plane writes, child reads.
- `registry: ConcurrentHashMap` — control plane writes, all threads read.

No shared mutable state requires explicit locking; atomics + `ConcurrentHashMap` cover it. The supervisor's `stop()` uses `Thread.join()` on its tick loop to guarantee no in-flight gate evaluation during flatten.

---

## 4. Migration

No DSL or file-format changes. A `PORTFOLIO ...` file written for 13b deploys identically; the daemon now interprets it correctly instead of failing.

The Phase 13b `PortfolioStrategy` is unchanged. It remains the runtime for the `Backtest` API. A doc comment at the top of `PortfolioStrategy.kt` points to `PortfolioSupervisor` as the daemon equivalent — no code split, just a navigation hint.

`Signal.CancelPendingForSymbol`, `OrderManager.cancelPendingForSymbol`, `ParsedFile`, `Dsl.parseFileAny`, `PortfolioLoader`, `PortfolioCompiled` — all reused as-is.

`StrategyRegistry.NAME_REGEX` widens to permit one slash for child entries. Existing strategy names remain valid.

`POST /deploy` and `GET /list` response shapes gain new fields (`kind`, `parent`) but existing fields are unchanged. CLI clients that don't read the new fields keep working.

---

## 5. Testing

### Unit tests

`PortfolioSupervisorTest` (new):
- `evaluates rule on tick and toggles child gateActive`
- `deactivate without HOLD calls child flatten`
- `deactivate with HOLD does not call flatten`
- `same-tick activation of one child + deactivation of another applies both`
- `stop blocks until in-flight tick completes`

`StrategyRegistryTest` (extended):
- `child names with slash are accepted`
- `top-level deploy with slash in name is rejected`
- `deploying portfolio when child name already exists rejects with 409`
- `cascade stop removes portfolio + all children atomically`

`ChildHandleGatingTest` (new):
- `pipeline with gate=false consumes candles but emits no signals`
- `gate=true plus operatorStop=true emits no signals`
- `gate flips on candle boundaries are honored on the next tick`

### Integration tests

`PortfolioDaemonTest` (new, real HTTP, no mocks):
- Deploy a portfolio with two children, one HOLD one not. Assert `GET /list` shows portfolio + 2 children.
- `GET /status/mybook` returns aggregate + children array. `GET /status/mybook/trend` returns child-level shape.
- Stop one child via `POST /stop/mybook/trend`; assert `operatorStop=true` in status, gate stays false even when the rule would activate.
- `POST /start/mybook/trend`; assert gate can re-activate next tick.
- Cascade stop the portfolio; assert all entries removed and child sessions closed.

### Existing tests that must keep passing

- `PortfolioBacktestTest`, `PortfolioStrategyTest`, `PortfolioLoaderTest`, `PortfolioBuilderTest`, `RoundTripEquivalenceTest` (PORTFOLIO cases) — backtest path is untouched.
- All Phase 12c daemon tests for top-level strategies.

---

## 6. Risks

**Medium — cascade-stop ordering.** Supervisor must halt before child flatten. Mitigation: `supervisor.stop()` is synchronous and blocks on its tick thread; cascade-stop waits for it before flattening any child. Test `stop blocks until in-flight tick completes` enforces this.

**Medium — flatten + concurrent broker fills.** A child being flattened while its broker is filling a prior order: the flatten signals enter the same signal bus and dispatch in order. No race so long as the bus is FIFO. Existing 12c broker contract guarantees FIFO; verify with a test that flatten-during-active-order produces correct final position = 0.

**Low — slash in registry keys.** New regex must reject `/foo`, `foo/`, `foo//bar`, `foo/bar/baz`. Test `top-level deploy with slash in name is rejected` covers `foo/bar`; add cases for the other malformed inputs.

**Low — log filename collision.** Two portfolios named `a` and `a-b`, child `a/b` would all map filename `a-b.log`. Mitigation: prefix child log filenames with a separator that strategies cannot use, e.g. `a__b.log` (double underscore), since `_` is allowed in names but `__` paired with the parent prefix is unique. Decision recorded: child log files use `<parent>__<alias>.log`.

**Low — observability port exhaustion.** Each child binds its own port. A portfolio with 50 children consumes 50 ports plus the supervisor's market subscription. Acceptable for v2 — operator concern, not a correctness concern.

**Low — indicator state on long-gated children.** A child gated off for an extended period skips `onCandle` entirely; its indicators do not advance. On reactivation, the first few rule evaluations may use stale or unwarmed indicator state. This matches 13b's `PortfolioStrategy` semantics. Future work may move the gate downstream of indicator warming so indicators stay current regardless of gate state.

---

## 7. References

- Phase 13b spec: `docs/superpowers/specs/2026-05-09-trading-engine-phase13b-design.md`
- Phase 13b changelog: `docs/phases/phase-13b.md`
- Phase 12c daemon spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase12c-design.md`
- 13b `PortfolioStrategy`: `src/main/kotlin/com/qkt/app/PortfolioStrategy.kt`
- 13b `PortfolioLoader`: `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt`
- Existing `StrategyRegistry`: `src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt`
- Existing `ControlRoutes`: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
