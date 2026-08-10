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

Never point daemon `--load-dir` at `strategies/armed/`. The armed strategy exists so
its exact DSL and risk contract can be reviewed before the later bounded-order
runner is implemented and approved.

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
  permissions, balance, leverage, and an initially flat account;
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
It is not evidence for order/fill/accounting parity, sustained stress, multiple
containers, QKT Insights, or production readiness; those remain later gates.
