# Local Live Validation

These scripts prepare and run reviewable QKT scenarios against an MT5 gateway bound
to `127.0.0.1`. They reject remote and tunnel URLs. The generated configuration
resolves `QKT_BROKER_API_KEY` at execution time; the key is never accepted as a
command argument or written to an artifact.

## Prepare

Use a fresh output directory and values read from the current demo account:

```bash
scripts/live-validation/prepare-scenario.sh \
  --output /var/tmp/qkt-validation/run-001 \
  --id validation_run_001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$CURRENT_BALANCE" \
  --expected-leverage "$CURRENT_LEVERAGE" \
  --magic "$UNIQUE_MAGIC"
```

The command creates:

- an isolated `qkt.config.yaml` with global, per-strategy, and book-risk caps;
- an M1/M5 read-only indicator strategy under `strategies/readonly/`;
- a separately isolated, one-entry `0.01`-lot strategy under `strategies/armed/`;
- expected account, cleanup, scenario, and source-checksum documents; and
- isolated data, state, log, journal, and evidence directories.

Never point daemon `--load-dir` at `strategies/armed/`. The armed strategy is
deployed explicitly only by the bounded-order runner after its safety gates pass.

## Verify And Run

Static verification performs no network calls:

```bash
scripts/live-validation/run-readonly.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --verify-only
```

The real read-only observation requires the existing environment credential:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-readonly.sh \
  --scenario /var/tmp/qkt-validation/run-001
```

The observation takes at least 310 seconds so one M5 boundary must close. It:

- verifies source hashes and parses every generated strategy;
- checks gateway health, kill-switch state, account identity, demo mode, trading
  permissions, starting balance and leverage, and an initially flat account;
- proves M1 and M5 history contains only closed, aligned, unique bars;
- compares QKT EMA output with an independent calculation over the captured closes;
- starts the real daemon with only `strategies/readonly/` and retains M1/M5 traces;
- samples process CPU, RSS, and thread count without adding JVM limits;
- validates engine-audit and MT5-transport JSONL;
- records exact DSL stream candles for both M1 and M5;
- requires every stale-market-data episode to recover before shutdown;
- proves no position, order, deal, balance, or equity change occurred; and
- creates a strict read-only golden bundle with zero fills and zero mutating gateway calls;
- removes the control token, scans for credential persistence, and writes size and
  SHA-256 manifests for every retained non-secret artifact.

Replay the sealed bundle offline through full-tick paper, full-tick MT5 simulation,
and plain-bar paper modes:

```bash
scripts/live-validation/compare-golden-replay.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --out /var/tmp/qkt-validation/run-001-replay
```

The comparison requires exact warmup and M1/M5 indicator traces, captured input
counts in full-tick modes, timeframe-complete bar materialization, and zero trading
or accounting output. This remains read-only evidence; order/fill parity, sustained
stress, QKT Insights, and production readiness are later gates.

## Isolated Container Load And Restart

Build an image whose embedded revision matches the clean checkout, then run two
isolated strategy/state/data mounts against the same local demo gateway:

```bash
docker build --build-arg QKT_GIT_SHA="$(git rev-parse --short=8 HEAD)" \
  -t qkt:live-validation .
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-container-load.sh \
  --output /var/tmp/qkt-validation/container-load-001 \
  --image qkt:live-validation \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$CURRENT_BALANCE" \
  --expected-leverage "$CURRENT_LEVERAGE"
```

The minimum eleven-minute observation runs eight M1/M5 streams over four symbols.
Each temporary strategy exercises EMA, RSI, SMA, and ATR mappings with global,
per-strategy, and book-risk configuration. The runner samples queue depth, dropped
ticks, tick-processing latency, CPU, memory, and process count; restarts one daemon
mid-run; proves the peer keeps receiving ticks and evaluating every stream; and
verifies source-directory auto-deployment plus restoration of persisted rule-edge
state. It does not claim deployment metadata can restore a strategy without its
source directory. The restart clock requires a full 310-second observation after
generation 2 reports ready, and every stream must produce an exact candle/evaluation
join before and after the restart where applicable.

This scenario intentionally emits no orders. Concurrent read-only containers isolate
load and restart failures without allowing separate daemons to contend for positions
on one account. The bounded demo scenarios own distinct magic numbers and test order,
fill, protection, rejection, reconciliation, and accounting behavior separately.
The runner records resource use but sets no JVM heap or container resource limit.
It rejects host, image, or container JVM override variables and retains sanitized
container-inspection evidence showing that Docker memory, CPU, PID, and CPU-set
restrictions are absent. State persistence remains asynchronous and off the engine
hot path.

The two containers poll each distinct symbol every `500 ms` and reconcile the flat
account every `5 s`. For four distinct symbols and two broker cycles, the configured
upper-bound gateway cadence is approximately 9.2 requests per second. These values,
the actual resource sample counts, exact warmup counts, restart timestamps, zero
mutating requests, and zero order/fill/accounting events are sealed in the result.

## Read-Only Catalog Wave 1

Prepare four checksummed catalog cases without contacting the gateway:

```bash
scripts/live-validation/prepare-readonly-catalog.sh \
  --output /var/tmp/qkt-validation/readonly-catalog-001 \
  --id readonly_catalog_001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$CURRENT_BALANCE" \
  --expected-leverage "$CURRENT_LEVERAGE"

scripts/live-validation/run-readonly-catalog-containers.sh \
  --suite /var/tmp/qkt-validation/readonly-catalog-001 \
  --verify-only
```

After building an image from the same clean commit, run all four cases concurrently:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-readonly-catalog-containers.sh \
  --suite /var/tmp/qkt-validation/readonly-catalog-001 \
  --output /var/tmp/qkt-validation/readonly-catalog-live-001 \
  --image qkt:live-validation-COMMIT
```

The positive cases cover numeric and candle indicators, math helpers, cross-symbol
and M1/M5 mappings, and session/history state. The fourth container keeps an M1
bars control running while a separate VWAP/OBV strategy must fail the live feed's
volume-capability gate. Every case requires warmup ticks, live ticks, constructed
bars joined to strategy evaluations, and named evaluation vectors. The aggregate
gate requires zero mutating transport calls, order events, fills, venue deals, and
account changes. It records runtime resource use without imposing JVM, CPU, memory,
PID, or CPU-set limits.

The four-container suite deliberately polls more slowly than a single production
daemon: each symbol is sampled every `500 ms`, and flat-account reconciliation runs
every `5 s`. Across the suite's five symbol streams and four broker cycles, this
reduces the configured gateway cadence from roughly 62 to 12.4 requests per second
while retaining two quote polls per second for live M1/M5 bar construction. The
sealed suite contract and final result both record these polling values.

Before launching the four JVMs, the runner validates UTC against the authoritative
broker tick clock and waits for a bounded startup phase 90-150 seconds after a
five-minute boundary.
This avoids asking MT5 for concurrent M1/M5 warmups while its recent-history cache is
rolling to a new M5 bucket. The wait is capped at 260 seconds, every observation is
retained, and a stale tick or any subsequent load-directory auto-deploy failure stops
the run immediately with evidence. The guard does not relax the engine's history
freshness check or retry a genuine time-base mismatch.

Each prepared stream also declares whether it is the rule-driving alias or a
dependency. Both roles require an exact `StreamCandleEvent` to
`StrategyCandleEvaluatedEvent` candle-window join; only rule drivers require a
positive `rulesEvaluated` count. Dependency values remain proven by their named
evaluation vectors. Runtime log evidence separately requires every in-window stale
episode to recover, rejects in-window feed disconnects and unrelated errors, and
counts final-shutdown disconnect warnings without treating them as runtime failures.

Market-contingent stateful values such as failed breaks, gap fills, and defended
initial-balance levels may be undefined when the observed history does not contain
that market pattern. Their retained vector proves that the mapped expression was
evaluated; it does not claim that every possible state transition occurred. Those
transitions require deterministic replay fixtures in a later coverage wave.
Running `SINCE OPEN`/`SINCE T-N` aggregates are also deferred: the current compiler
requires a symbol-associated action for aggregate rule context, while this wave
permits only `LOG`. They must be exercised in a deterministic non-live fixture or
after read-only aggregate context is supported; this harness does not disguise that
gap by adding an order action.

## QKT Insights Attribution And Replay

Use an exact local QKT Insights image and a fresh prepared scenario:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
export QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
scripts/live-validation/run-insights-attribution.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --insights-image qkt-insights:validation-COMMIT \
  --arm I_UNDERSTAND_DEMO_ORDER_0.01
```

The runner starts an isolated Insights container and one real QKT daemon with an
M1/M5 read-only strategy. After observing a closed bar, it stops the collector,
deploys the separately armed `0.01`-lot bracket, and requires unacknowledged Insights
envelopes in QKT's durable spool. It restarts the same collector and database,
requires replay to drain, repeatedly verifies that the open venue ticket remains
attributed only to the armed owner, then flattens through QKT and checks durable order
and deal attribution. Runtime credentials are generated in memory and scanned against
the retained artifacts. The runner sets no JVM or container memory limit.

## Bounded Demo Bracket

Static verification of the armed scenario performs no network or trading calls:

```bash
scripts/live-validation/run-market-bracket.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --verify-only
```

Live execution is deliberately difficult to invoke accidentally. It needs a freshly
prepared scenario, an initially flat allowlisted account, the broker credential, and
both exact confirmations:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
export QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
scripts/live-validation/run-market-bracket.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --arm I_UNDERSTAND_DEMO_ORDER_0.01
```

The runner starts an empty daemon, explicitly deploys the single armed strategy,
allows one `0.01`-lot EURUSD market entry with attached `0.0030` stop and `0.0060`
target distances, and records the magic-scoped venue position. It then invokes
QKT's broker-verified kill/flatten path, stops the strategy and daemon, and requires:

- no remaining account-wide or magic-scoped position or pending order;
- matching entry and exit deals for the owned position ticket;
- balance change equal to profit, commission, swap, and fee from those deals;
- accepted and filled engine-audit lifecycle events;
- successful MT5 `/order` and `/close_position` transport records; and
- a final flat, tradeable demo-account snapshot.

The generated broker profile does not set `expected_leverage`: Exness can change demo
account leverage while login, server, mode, and currency remain the same. Each run still
requires the prepared leverage at startup and records both initial and final leverage,
including whether it changed. Production operators may use exact `expected_leverage`
only for accounts whose venue contract makes leverage immutable.

On failure, the exit trap queries only the scenario magic and attempts to close or
cancel only its owned tickets. This single bracket is the first execution proof, not
coverage of the remaining order lifecycle matrix.
