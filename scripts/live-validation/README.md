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

## Read-Only Already-Deployed Resync

Use the same freshly prepared scenario to prove the live daemon control-plane path
without allowing any trading:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-readonly-resync.sh \
  --scenario /var/tmp/qkt-validation/run-001
```

The runner starts the daemon with no `--load-dir`, requires an initially empty
deployment set, deploys the prepared read-only strategy through `qkt deploy`,
waits for exact `1m` and `5m` closed-bar evidence, then resyncs the same deployed
name to a generated read-only replacement through `qkt resync`. It requires exact
`StreamCandleEvent` to `StrategyCandleEvaluatedEvent` joins before and after the
replacement, exact warmup pseudo-tick counts for both generations, zero order/fill/
accounting events, zero mutating gateway calls, flat final venue state, and an
unchanged account snapshot. The daemon journal must retain both `deploy` and
`resync` actions.

## Read-Only Gateway Restart

Use the prepared read-only scenario to prove live feed disconnect and reconnect
recovery against the real local MT5 gateway container:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-readonly-gateway-restart.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --gateway-container lab-mt5-gateway
```

The runner waits for exact pre-restart `1m` and `5m` matched stream/evaluation
evidence, restarts the named Docker gateway container, requires a retained feed
disconnect warning and reconnect info message, waits for the gateway to return to
`healthy` and `connected`, then requires exact post-restart `1m` and `5m` matched
evidence again. The account must remain flat, venue history must remain empty, the
MT5 transport must stay read-only, startup warmup pseudo-tick counts must remain
exact before the restart, no reconnect warmup may be emitted after the restart,
and retained container-inspection evidence must redact the gateway API key.

## Read-Only Deployed Gateway Restart

Use the prepared read-only scenario to prove a control-plane deployed strategy
survives a real gateway restart without redeploy:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-readonly-deployed-gateway-restart.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --gateway-container lab-mt5-gateway
```

The runner starts the daemon with no `--load-dir`, requires an empty deployment
set, waits for the bounded safe broker startup phase, deploys the prepared
read-only strategy through `qkt deploy`, requires exact pre-restart `1m` and `5m`
matched evidence, restarts the gateway container, then requires exact post-restart
`1m` and `5m` matched evidence again without any reconnect warmup replay. The
account must remain flat, venue history must remain empty, the MT5 transport must
stay read-only, the daemon journal must retain the `deploy` action, and retained
container-inspection evidence must redact the gateway API key.

## Order-Bearing Gateway Restart

Use the prepared bounded-order scenario to prove that a real strategy-owned demo
position survives a real gateway restart and still closes through the intended
strategy path:

```bash
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
export QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN="$DEMO_LOGIN"
export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER="$DEMO_SERVER"
scripts/live-validation/run-order-gateway-restart.sh \
  --scenario /var/tmp/qkt-validation/run-001 \
  --gateway-container lab-mt5-gateway \
  --arm I_UNDERSTAND_DEMO_ORDER_0.01
```

The runner starts the daemon with only the prepared read-only sibling, waits for
exact pre-restart `1m` and `5m` matched bar/evaluation evidence, deploys the
prepared armed strategy, waits for exactly one real `0.01`-lot position to open,
restarts the gateway container while that ticket is still open, requires retained
disconnect and reconnect proof, then requires the same strategy to close the same
ticket after reconnect. The final gate requires exact two-deal venue history,
exact post-restart matched `1m` and `5m` evidence for both the read-only sibling
and the armed strategy, zero dropped ticks, final-flat account reconciliation,
and deal-net equality with the account balance delta.

Retained gateway/container artifacts are identity-safe in this runner: gateway
health evidence is stored through a safe snapshot that omits `mt5_account`, and
container inspection redacts `API_KEY=`, `MT5_PASSWORD=`, `MT5_LOGIN=`, and
`MT5_SERVER=` before the final artifact scan. The scan rejects only unredacted
password metadata.

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
scripts/live-validation/prepare-scenario.sh \
  --output /var/tmp/qkt-validation/run-001 \
  --id insights_e97e95a9 \
  --gateway-url http://127.0.0.1:5001 \
  --runtime-account-identity \
  --expected-balance "$DEMO_BALANCE" \
  --expected-leverage "$DEMO_LEVERAGE" \
  --magic "$ISOLATED_MAGIC"

export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_LOGIN="$DEMO_LOGIN"
export QKT_BROKER_EXNESS_EXPECTED_ACCOUNT_SERVER="$DEMO_SERVER"
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
and deal attribution. Prepare this scenario with `--runtime-account-identity`; login
and server are required only in the runtime environment. Raw account responses are
reduced to non-identifying financial/status evidence, account transport records and
startup logs are sanitized, live-state samples retain only ticket/strategy/state
attribution fields, and a final scan fails closed if identity remains.
Runtime credentials are generated in memory and scanned against the retained
artifacts. The runner sets no JVM or container memory limit.

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

Armed runs are serialized per demo account, not globally. The runner acquires
`flock` on `/var/tmp/qkt-validation/LIVE-LOCK-<server>-<login>`, so different
local gateways can be used independently when they point at different demo
accounts. Before deploy, the runner also verifies that the host CLI build SHA
matches the prepared scenario, probes `qkt bot bars` readiness on `1m` and `5m`,
and requires a fresh venue tick after daemon startup so replay-authoritative
live passes do not begin from stale data.

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

## Concurrent Bounded Round Trips

Prepare two distinct bounded scenarios against the same localhost gateway, one for
`EURUSD` and one for `GBPUSD`:

```bash
scripts/live-validation/prepare-scenario.sh \
  --output /var/tmp/qkt-validation/roundtrip-eurusd-001 \
  --id roundtrip_eurusd_001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$DEMO_BALANCE" \
  --expected-leverage "$DEMO_LEVERAGE" \
  --magic 920001 \
  --symbol EURUSD

scripts/live-validation/prepare-scenario.sh \
  --output /var/tmp/qkt-validation/roundtrip-gbpusd-001 \
  --id roundtrip_gbpusd_001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$DEMO_BALANCE" \
  --expected-leverage "$DEMO_LEVERAGE" \
  --magic 920002 \
  --symbol GBPUSD
```

Verify the pair statically first:

```bash
scripts/live-validation/run-container-round-trips.sh \
  --scenario-a /var/tmp/qkt-validation/roundtrip-eurusd-001 \
  --scenario-b /var/tmp/qkt-validation/roundtrip-gbpusd-001 \
  --verify-only
```

Then run the real two-container exercise:

```bash
docker build --build-arg QKT_GIT_SHA="$(git rev-parse --short=8 HEAD)" \
  -t qkt:live-validation .
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
export QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
scripts/live-validation/run-container-round-trips.sh \
  --scenario-a /var/tmp/qkt-validation/roundtrip-eurusd-001 \
  --scenario-b /var/tmp/qkt-validation/roundtrip-gbpusd-001 \
  --output /var/tmp/qkt-validation/roundtrip-live-001 \
  --image qkt:live-validation \
  --arm I_UNDERSTAND_TWO_CONCURRENT_DEMO_ORDERS_0.01
```

This runner is the strongest current live order-path proof in the repo. It starts two
unrestricted QKT containers against one localhost MT5 demo gateway, deploys both
strategies nearly simultaneously, and requires:

- one owned `0.01`-lot entry and one owned exit per strategy;
- exact M1 and M5 candle/evaluation joins for each strategy;
- indicator-entry and indicator-exit traces retained from the live daemon logs;
- one MT5 order placement, one protection placement, and one close mutation per case;
- two accepted, two filled, and two accounted lifecycle events per case with no
  rejections;
- strict magic-scoped cleanup and a final flat account with zero pending orders; and
- no JVM, Docker memory, CPU, PID, or CPU-set restriction.

It proves that two live strategies can share the same demo account on different
symbols while retaining strict per-strategy attribution, bounded protection, and
accounting cleanup. It does not prove the broader same-symbol shared-account
serialization problem or the rest of the order-lifecycle matrix.

## Parallel Risk-Rejection Matrix

Prepare the five-case pre-transport rejection suite against the same localhost
gateway:

```bash
scripts/live-validation/prepare-risk-rejection-matrix.sh \
  --output /var/tmp/qkt-validation/riskreject-001-suite \
  --id riskreject001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$CURRENT_BALANCE" \
  --expected-leverage "$CURRENT_LEVERAGE" \
  --magic-base 930001
```

Verify the suite contract without network or Docker execution:

```bash
scripts/live-validation/run-risk-rejection-containers.sh \
  --suite /var/tmp/qkt-validation/riskreject-001-suite \
  --verify-only
```

Then run the real localhost matrix from a clean checkout whose host CLI and image
both match the same commit:

```bash
docker build --build-arg QKT_GIT_SHA="$(git rev-parse --short=8 HEAD)" \
  -t qkt:live-validation .
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-risk-rejection-containers.sh \
  --suite /var/tmp/qkt-validation/riskreject-001-suite \
  --output /var/tmp/qkt-validation/riskreject-001-live \
  --image qkt:live-validation
```

This runner launches five unrestricted QKT containers in parallel against the
same flat demo account. Each strategy emits a fixed `0.01`-lot intent on the
next synchronized even-minute boundary, and each case must be rejected before any
broker submission or MT5 mutation. The current five reviewed cases are:

- `max-quantity`
- `max-notional`
- `far-price-collar`
- `measured-usage`
- `operator-halt`

For each case the retained audit must prove exactly one causal chain:

- one `RuleDecisionEvent`
- one `DecisionOrderLinkedEvent`
- one `RiskRejectedEvent`
- zero `OrderEvent`
- zero broker accepts/rejects/fills
- zero mutating transport requests

The aggregate gate also requires:

- five parallel containers with no Docker CPU, memory, PID, or cpuset limits;
- no JVM override or heap restriction;
- unchanged flat venue state before and after the run;
- unchanged account financial state; and
- no retained broker credential in the artifacts.

This is a real live edge-case slice, but it is intentionally limited to static
pre-transport risk rejection. Stateful risk cases now have separate deterministic
fixture coverage below; this slice does not claim to cover them.

## Deterministic Stateful Risk Matrix

Prepare the four-case restored-state matrix against the same localhost gateway:

```bash
scripts/live-validation/prepare-stateful-risk-matrix.sh \
  --output /var/tmp/qkt-validation/stateful-risk-001-suite \
  --id statefulrisk001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$CURRENT_BALANCE" \
  --expected-leverage "$CURRENT_LEVERAGE" \
  --magic-base 931001
```

Verify the suite contract without network or Docker execution:

```bash
scripts/live-validation/run-stateful-risk-containers.sh \
  --suite /var/tmp/qkt-validation/stateful-risk-001-suite \
  --verify-only
```

Then run the real localhost matrix from a clean checkout whose host CLI and image
both match the same commit:

```bash
docker build --build-arg QKT_GIT_SHA="$(git rev-parse --short=8 HEAD)" \
  -t qkt:live-validation .
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
scripts/live-validation/run-stateful-risk-containers.sh \
  --suite /var/tmp/qkt-validation/stateful-risk-001-suite \
  --output /var/tmp/qkt-validation/stateful-risk-001-live \
  --image qkt:live-validation
```

This runner launches four unrestricted QKT containers in parallel against the
same flat demo account. Each case restores a deterministic `risk-state.json`,
waits for the real halt rule to trip on live `1m` bars/ticks, and then requires
one causally linked `RiskRejectedEvent` before MT5 transport. The reviewed cases
are:

- `global-daily-loss`
- `strategy-daily-loss`
- `global-drawdown`
- `loss-streak`

For each case the retained audit must prove:

- at least one `StreamCandleEvent` and one `StrategyCandleEvaluatedEvent`
- exactly one `RiskEvent.Halted`
- exactly one `RuleDecisionEvent`
- exactly one `DecisionOrderLinkedEvent`
- exactly one `RiskRejectedEvent`
- zero `OrderEvent`
- zero broker accepts/rejects/fills
- zero mutating transport requests

The aggregate gate also requires:

- four parallel containers with no Docker CPU, memory, PID, or cpuset limits;
- no JVM override or heap restriction;
- unchanged flat venue state before and after the run;
- unchanged account financial state; and
- no retained broker credential in the artifacts.

This slice is now backed by a retained localhost MT5 passing result at
`/var/tmp/qkt-validation/stateful-risk-20260811T162402Z-live-thin/evidence/result.json`.
It closes the deterministic fixture path for daily-loss, drawdown, and
loss-streak.

## Controlled Margin-Floor Fixture

Prepare the next remaining live stateful-risk fixture:

```bash
scripts/live-validation/prepare-margin-floor-fixture.sh \
  --output /var/tmp/qkt-validation/margin-floor-001-suite \
  --id marginfloor001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$CURRENT_BALANCE" \
  --expected-leverage "$CURRENT_LEVERAGE" \
  --magic-base 931401
```

This preparer does not contact the gateway. It produces:

- an opener role with a real bounded `0.01`-lot strategy and a fixed
  `margin_floor_pct: "0"` config;
- a probe role with a second bounded `0.01`-lot strategy and a
  `qkt.config.template.yaml` placeholder for a runtime-selected
  `margin_floor_pct`;
- a machine-readable rule for selecting that runtime floor from the observed
  live `margin_level`: `ceil(observed_margin_level_pct) + 1`; and
- checksummed contracts for a real-open opener path plus a zero-transport
  `MarginFloor` rejection path.

The intended live runner flow is:

1. start the opener role and retain exactly one real strategy-owned demo
   position;
2. read the live account `margin_level` from the localhost gateway;
3. materialize the probe config with a floor above that observed level;
4. deploy the probe role and require one causal
   `RuleDecisionEvent -> DecisionOrderLinkedEvent -> RiskRejectedEvent` chain
   with zero `OrderEvent`, zero fills, and zero mutating gateway requests for
   the probe path; and
5. flatten the opener path and return the full account to zero positions and
   zero pending orders.

Fresh retained localhost MT5 passing evidence now exists for this fixture at
`/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/evidence/result.json`.
That retained run proved:

- one real bounded `0.01`-lot opener position was created on the local Exness
  demo account;
- the probe role materialized `margin_floor_pct = ceil(observed_margin_level_pct) + 1`
  from the live `/account` snapshot while the opener position remained open;
- the probe retained one exact
  `RuleDecisionEvent -> DecisionOrderLinkedEvent -> RiskRejectedEvent` chain
  with zero `OrderEvent`, zero fills, and zero mutating gateway requests; and
- the opener flatten path returned the account to flat with zero pending orders
  and retained deal-net to balance-delta reconciliation.

Run the real localhost fixture from a clean checkout whose host CLI and Docker
image match the same commit:

```bash
docker build --build-arg QKT_GIT_SHA="$(git rev-parse --short=8 HEAD)" \
  -t qkt:live-validation .
export QKT_BROKER_API_KEY="$LOCAL_GATEWAY_KEY"
export QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
scripts/live-validation/run-margin-floor-fixture.sh \
  --fixture /var/tmp/qkt-validation/margin-floor-001-suite \
  --output /var/tmp/qkt-validation/margin-floor-001-live \
  --image qkt:live-validation \
  --arm I_UNDERSTAND_DEMO_ORDER_0.01
```

The live runner is designed to:

- start the opener role in one unrestricted localhost container;
- open exactly one real `0.01`-lot EURUSD demo position;
- derive `margin_floor_pct = ceil(observed_margin_level_pct) + 1` from the live
  `/account` snapshot while that opener position is still open;
- materialize the probe config and run it in a second unrestricted localhost
  container;
- retain one causal
  `RuleDecisionEvent -> DecisionOrderLinkedEvent -> RiskRejectedEvent` chain for
  the probe path with zero `OrderEvent`, zero fills, and zero mutating gateway
  requests; and
- flatten the opener path and reconcile the final account back to zero
  positions and zero pending orders.

## Generated Order-Bearing Parity Wave

Prepare the first generated order-bearing wave for the next live/replay parity
phase:

```bash
scripts/live-validation/prepare-generated-parity-wave.sh \
  --output /var/tmp/qkt-validation/generated-wave-001 \
  --id generatedwave001 \
  --gateway-url http://127.0.0.1:5001 \
  --expected-login "$DEMO_LOGIN" \
  --expected-server "$DEMO_SERVER" \
  --expected-balance "$CURRENT_BALANCE" \
  --expected-leverage "$CURRENT_LEVERAGE" \
  --magic-base 931901
```

This preparer does not contact the gateway. It generates four bounded
`0.01`-lot scenarios that reuse the existing `run-readonly.sh`,
`run-market-bracket.sh`, and `compare-golden-replay.sh` contracts:

- `ema-eurusd` using the existing `ema_cross` armed variant;
- `rsi-gbpusd` using `rsi_reversion`;
- `atr-eurusd` using `atr_channel`; and
- `case-gbpusd` using `case_math`.

Each generated case retains:

- one read-only companion strategy for the shared closed-bar checks;
- one armed `*_market_bracket.qkt` strategy that still emits the canonical
  bounded indicator entry and exit traces;
- a scenario-local `expected.json`, `scenario.json`, and `SHA256SUMS`; and
- suite-level metadata in `suite.json` plus a top-level `SHA256SUMS`.

This wave is now sealed across clean live plus replay for all four generated
cases. Authoritative retained evidence:

- `ema-eurusd`:
  `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z/evidence/result.json`
  and
  `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z-replay-20260811T182759Z/result.json`
- `rsi-gbpusd`:
  `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z/evidence/result.json`
  and
  `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z-replay-20260811T180849Z/result.json`
- `case-gbpusd`:
  `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z/evidence/result.json`
  and
  `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z-replay-20260811T185752Z/result.json`
- `atr-eurusd`:
  `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z/evidence/result.json`
  and
  `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z-replay-20260811T191243Z/result.json`

The wave remains the first concrete entry point for the broader generated
single-strategy live/replay/backtest parity matrix described in `../notes.txt`.
It does not replace the existing runners; it feeds them with a broader set of
bounded scenarios.

## Offline Replay Comparison For A Passed Round Trip

After a round-trip case passes, compare the retained golden capture with offline
replay modes:

```bash
scripts/live-validation/compare-container-round-trip-replay.sh \
  --scenario /var/tmp/qkt-validation/roundtrip-eurusd-001 \
  --out /var/tmp/qkt-validation/roundtrip-eurusd-001-replay
```

The comparator never contacts the gateway. It materializes the sealed capture and
runs exactly:

- `full-ticks-paper`
- `full-ticks-mt5`
- `bars-paper`

For each replay it requires two fills, zero rejections, complete reports, and retained
artifacts. It also checks that the capture includes warmup ticks, live ticks, bars,
stream-candle events, strategy-candle evaluations, linked placements, and the canonical
live bracket evidence. This is the current bridge between real local MT5 execution and
offline parity over ticks, bars, and MT5-sim for the same retained scenario.
