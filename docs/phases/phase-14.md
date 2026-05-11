# Phase 14 — Portfolio v2: daemon fan-out + per-child observability

**Released:** 2026-05-10
**Version:** 0.16.0

## Summary

Phase 14 makes `.qkt` portfolio files first-class in the daemon. Phase 13b shipped the `PORTFOLIO` file format and the `Backtest`-API runtime; the daemon (`qkt deploy/list/status/stop/logs`) had no understanding of portfolio files. Phase 14 closes that gap by fanning each portfolio out into per-child `LiveSession`s under a new `PortfolioSupervisor` that owns rule evaluation and gate transitions. Each child gets its own port, log file, broker, and PnL — `qkt status mybook/trend` drills into a single child without losing the portfolio-level aggregate at `qkt status mybook`. A new `qkt start <portfolio>/<child>` verb clears operator-stop on a paused child.

The `PortfolioStrategy` from Phase 13b is unchanged — it remains the runtime for the `Backtest` API. Phase 14 is the parallel daemon-side runtime.

## What's new

- `qkt deploy mybook.qkt` accepts portfolio files; response includes `kind: "portfolio"` plus a `children` array.
- `qkt list` shows portfolios + their children with `kind` (`strategy`/`portfolio`/`child`) and `parent` fields. CLI indents child rows.
- `qkt status mybook` returns an aggregate (`equity`/`balance`/`realized`/`unrealized` summed across children) plus a `children` array with per-child `gateActive`/`operatorStop`/`hold`/`port`/`trades`/`realized`/`unrealized`.
- `qkt status mybook/trend` returns the child's own `/status` augmented with `parent`, `alias`, `gateActive`, `operatorStop`, `hold`.
- `qkt stop mybook` cascade-stops the supervisor and every child (no-HOLD children flatten first).
- `qkt stop mybook/trend` operator-stops a single child (`operatorStop=true`, gate forced false, flatten if no HOLD); the portfolio keeps running.
- `qkt start mybook/trend` (new verb) clears `operatorStop` on a child so the supervisor's gate can re-activate it on the next rule eval.
- `qkt logs mybook` returns the supervisor log; `qkt logs mybook/trend` returns the child's log. Filenames substitute `/` with `__`.
- New `PortfolioSupervisor` evaluates `AlwaysRun` immediately at start and `WhenRun` per candle, diffing desired-active against current `gateActive` and calling `child.flatten()` on no-HOLD deactivations.
- New `PortfolioDeployer` spins up child sessions + supervisor with rollback on partial failure (no half-started portfolio state in the registry).
- `TradingPipeline` gains a `gate: () -> Boolean` parameter; `LiveSession` plumbs the gate to its pipeline and exposes `flatten()` on `LiveSessionHandle` for operator-driven position closes.
- `StrategyHandle` gains an optional `childMeta` field (`parent`/`alias`/`hold`/`gateActive`/`operatorStop`); `StrategyRegistry` widens its name regex to permit `<portfolio>/<alias>` and adds `registerPortfolio`/`getPortfolio`/`listPortfolios`/`childrenOf`/`removePortfolio`.

## Migration from Phase 13b

No DSL or file-format changes — a `PORTFOLIO ...` file written for 13b deploys against the 14 daemon unchanged. The 13b `PortfolioStrategy` is the backtest-path runtime and stays as-is.

| Before (13b daemon) | After (14 daemon) |
|---|---|
| `qkt deploy mybook.qkt` (portfolio file) → undefined behavior | Returns `kind: "portfolio"`, fans out child sessions |
| `/list` rows shape: `{name, port, trades, uptimeMs, state}` | Same fields plus `kind` and (children only) `parent`, `gateState` |
| `/status/<name>` returned the strategy snapshot | Strategy: unchanged. Child: snapshot + `parent`/`alias`/`gateActive`/`operatorStop`/`hold`. Portfolio: aggregate + `children` array |
| `/stop/<name>` only stopped strategies | Strategy: unchanged. Child: operator-stop. Portfolio: cascade |
| No `/start` route | New `POST /start/<child>` clears operator-stop |
| `StateDir.logFile("a/b")` produced `a/b.log` (subdir) | Now produces `a__b.log` (single file) |

## Usage cookbook

### Deploying a portfolio

A portfolio file with two children, gated by a parent-level condition:

```qkt
PORTFOLIO mybook VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
IMPORT 'trend.qkt' AS trend
IMPORT 'range.qkt' AS range HOLD
RULES
    WHEN btc.close > 100 RUN trend
    RUN range
```

Deploy:

```bash
$ qkt deploy mybook.qkt
{"name":"mybook","kind":"portfolio","state":"running",
 "startedAt":"2026-05-10T12:34:56.789Z",
 "children":[
   {"alias":"trend","name":"mybook/trend","port":54211,"hold":false},
   {"alias":"range","name":"mybook/range","port":54212,"hold":true}
 ]}
```

### Listing entries (CLI)

```bash
$ qkt list
NAME                KIND       UPTIME   PORT     TRADES   STATE
mybook              portfolio  00:01:23 -        -        running
  mybook/range      child      00:01:23 54212    14       running
  mybook/trend      child      00:01:23 54211    28       running
rsi-only            strategy   00:05:00 54210    17       running
```

### Per-child drill-down

```bash
$ qkt status mybook/trend
{"strategy":"mybook/trend","kind":"child","parent":"mybook","alias":"trend",
 "gateActive":true,"operatorStop":false,"hold":false,
 "version":1,"uptimeMs":123456,"startedAt":"...",
 "equity":"10042.13","realized":"42.13","unrealized":"0.00", ...}
```

### Aggregate portfolio view

```bash
$ qkt status mybook
{"name":"mybook","kind":"portfolio","version":1,"startedAt":"...",
 "uptimeMs":123456,"supervisorRunning":true,
 "equity":"10042.13","balance":"10000.00",
 "realized":"42.13","unrealized":"0.00",
 "children":[
   {"alias":"trend","name":"mybook/trend","port":54211,
    "gateActive":true,"operatorStop":false,"hold":false,
    "trades":28,"realized":"42.13","unrealized":"0.00"},
   {"alias":"range","name":"mybook/range","port":54212,
    "gateActive":false,"operatorStop":false,"hold":true,
    "trades":14,"realized":"0.00","unrealized":"0.00"}
 ]}
```

### Operator-stopping and resuming a child

```bash
$ qkt stop mybook/trend
{"name":"mybook/trend","state":"operator_stopped","trades":28}

$ qkt start mybook/trend
{"name":"mybook/trend","state":"resumed"}
```

`qkt stop mybook/trend` sets `operatorStop=true` and forces `gateActive=false` (the gate enforces `gateActive AND NOT operatorStop`). If `hold=false` for that child, its open positions flatten through its broker. The portfolio keeps running with the surviving children.

`qkt start mybook/trend` clears `operatorStop`. The next supervisor tick re-evaluates the rule and may re-activate the gate.

### Cascade stop

```bash
$ qkt stop mybook
{"name":"mybook","state":"stopped","trades":42}
```

Cascade stop halts the supervisor first, then for each child: flattens (if no HOLD), closes the session and observability server, and removes the registry entry. Children with `HOLD` keep their positions open at flatten time but their sessions still close.

### Following a child's logs

```bash
$ qkt logs mybook/trend --lines 50 --follow
2026-05-10 12:34:56.789 INFO  [mybook/trend] entry buy 0.1 BTCUSDT @ 50125.00
...
```

The supervisor logs gate transitions to `mybook.log`:

```bash
$ qkt logs mybook
2026-05-10 12:34:01.123 INFO  [mybook] trend activated
2026-05-10 12:34:31.456 INFO  [mybook] range deactivated, hold=true
```

### Composition with strategies

A daemon can run portfolios and standalone strategies side-by-side. They live in the same registry, distinguished by `kind`:

```bash
$ qkt deploy rsi-only.qkt
$ qkt deploy mybook.qkt
$ qkt list
NAME                KIND       UPTIME   PORT     TRADES   STATE
mybook              portfolio  00:00:05 -        -        running
  mybook/range      child      00:00:05 54213    0        running
  mybook/trend      child      00:00:05 54214    0        running
rsi-only            strategy   00:00:30 54212    3        running
```

## Testing patterns

`PortfolioSupervisor` gate logic is testable without HTTP via a stub `LiveSessionHandle` and the internal `applyDesired(map)` accessor. The pattern from `PortfolioSupervisorTest`:

```kotlin
val flattened = AtomicBoolean(false)
val a = stubChildHandle(parent = "p", alias = "a", hold = false,
                        flattenSpy = { flattened.set(true) })
val ast = PortfolioAst(name = "p", version = 1, streams = emptyList(),
                       imports = listOf(ImportClause("a.qkt", "a")),
                       rules = listOf(AlwaysRun("a")))
val supervisor = PortfolioSupervisor(ast, listOf(a), marketSource = null)
supervisor.start()
assertThat(a.gateActive.get()).isTrue
supervisor.applyDesired(mapOf("a" to false))
assertThat(flattened.get()).isTrue
```

`TradingPipeline` gate logic is testable by injecting an `AtomicBoolean`:

```kotlin
val gate = AtomicBoolean(false)
val pipeline = TradingPipeline(/* ... */, gate = { gate.get() })
// strategy.onTick fires, but emit() is suppressed when gate is false
```

`StrategyRegistry` slash-name validation is regex-level:

```kotlin
registry.deploy("mybook/trend", file)            // accepted
registry.deploy("foo//bar", file)                 // rejected: invalid name
registry.deploy("foo/bar/baz", file)              // rejected: invalid name
```

## Known limitations

- **`WEIGHT` clause not supported.** Capital allocation across children is whatever each child file declares; the portfolio cannot rebalance.
- **Import-time overrides not supported.** `IMPORT 'a.qkt' AS a OVERRIDE { ... }` would let one portfolio reuse a child file with different defaults; deferred to a future phase.
- **Nested portfolios not supported.** `IMPORT` can only reference strategy files. The loader rejects nested portfolios at parse time.
- **No `qkt reload <portfolio>` verb.** To pick up a file edit, `qkt stop mybook` then `qkt deploy mybook.qkt`.
- **End-to-end HTTP integration test deferred.** Phase 14 unit tests cover supervisor rule eval, registry slash-name validation + portfolio conflict rejection, and pipeline gate suppression. Real-HTTP coverage of the full `deploy/list/status/stop/start/cascade-stop` lifecycle is left as a follow-up. The route layer is exercised indirectly via the existing daemon route tests for strategies (`StatusProxyTest`, `StopRouteTest`, `ListRouteTest`).
- **Indicator state on long-gated DSL children stays current via the candle hub.** The hub feeds candles regardless of gate state, so DSL children's indicators advance even when their gate is false. Non-DSL `Strategy` implementations would not warm under gate, but only DSL strategies can be portfolio children today.
- **Pre-existing `LoadDirTest` flake.** The `--load-dir auto-deploys ...` test races between control-port-write and auto-deploy completion. Pre-existing on `main`; not introduced by Phase 14.

## References

- Spec: [`docs/superpowers/specs/2026-05-10-trading-engine-phase14-design.md`](../superpowers/specs/2026-05-10-trading-engine-phase14-design.md)
- Plan: [`docs/superpowers/plans/2026-05-10-trading-engine-phase14.md`](../superpowers/plans/2026-05-10-trading-engine-phase14.md)
- Phase 13b changelog: [`docs/phases/phase-13b.md`](phase-13b.md)
- Phase 12c changelog: [`docs/phases/phase-12c-daemon.md`](phase-12c-daemon.md)
