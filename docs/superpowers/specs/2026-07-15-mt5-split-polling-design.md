# MT5 Split Polling Design

Issue: #819

## Problem

One MT5 profile interval currently controls two workloads with different latency needs:
live quote ingestion and venue position/pending-order reconciliation. The daemon already
shares quote subscriptions and coalesces identical account reads, but production evidence
still shows gateway queueing when several symbols and strategy sessions are active.

Changing the existing interval alone is unsafe. A slower value reduces gateway load but
also delays candles and entry triggers; a faster value improves quotes but multiplies
position and pending-order reads.

## Contract

- `poll_interval_ms` remains the position and pending-order reconciliation cadence.
- `tick_poll_interval_ms` controls only MT5 live quote polling.
- Both values are positive `Long` milliseconds.
- Defaults remain 1000 ms.
- A profile that explicitly configures only `poll_interval_ms` preserves the historical
  coupled behavior. Setting both fields opts into independent tuning.
- Inheritance and environment overrides follow the existing broker-profile rules.
- Backtest and replay paths are unchanged.

## Runtime shape

`Mt5MarketSource` passes `tickPollIntervalMs` to `Mt5TickFeedSource`. `MT5PositionPoller`
and `MT5PendingOrderPoller` continue reading `pollIntervalMs`. The existing daemon-level
shared MT5 client and single-flight read cache remain the ownership boundary for venue
snapshots, so strategy-local attribution and event ordering do not change.

The hot-path cost is unchanged per delivered tick. Operators can lower gateway request
pressure by increasing the quote interval, at the explicit cost of up to that interval
of additional quote/candle latency. Increasing reconciliation cadence adds the same bound
to pending-fill, external-cancel, and OCO-sibling detection.

## Verification

- Loader tests cover defaults, legacy coupling, independent YAML tuning, environment
  overrides, inheritance, and positive-value validation.
- Market-source behavior proves quote polling uses the new field while reconciliation
  can remain independently slow.
- Existing MT5 broker/poller tests prove reconciliation continues to use
  `pollIntervalMs`.
- Production rollout compares gateway route rates, Waitress queue warnings, stale-data
  transitions, container health, and accepted-order reconciliation before and after.

## Non-goals

- Weakening the market-data stale threshold.
- Sharing mutable strategy attribution or broker state across sessions.
- Changing broker HTTP payloads, execution events, or backtest behavior.
