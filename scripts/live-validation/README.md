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
- requires every stale-market-data episode to recover before shutdown;
- proves no position, order, deal, balance, or equity change occurred; and
- removes the control token, scans for credential persistence, and writes size and
  SHA-256 manifests for every retained non-secret artifact.

This is read-only feed, candle, indicator, daemon, journal, and resource evidence.
It is not evidence for order/fill/accounting parity, sustained stress, QKT Insights,
or production readiness; those remain later gates.

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

The minimum seven-minute observation runs eight M1/M5 streams over four symbols.
Each temporary strategy exercises EMA, RSI, SMA, and ATR mappings with global,
per-strategy, and book-risk configuration. The runner samples queue depth, dropped
ticks, tick-processing latency, CPU, memory, and process count; restarts one daemon
mid-run; proves the peer remains healthy; and verifies automatic redeployment from
the persisted state directory. It retains valid audit journals and requires all
streams to emit after warmup.

This scenario intentionally emits no orders. Concurrent read-only containers isolate
load and restart failures without allowing separate daemons to contend for positions
on one account. The bounded demo scenarios own distinct magic numbers and test order,
fill, protection, rejection, reconciliation, and accounting behavior separately.
The runner records resource use but sets no JVM heap or container memory limit.

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
