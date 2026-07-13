# Tick-feed audit

`qkt audit-ticks` measures statistical drift between two tick sources for the
same symbol — typically TradingView (what a strategy *sees* during authoring)
vs the MT5 gateway (what the strategy *fills against* during live trading).
Run this before committing capital so the drift bound is known.

For deployments where MT5 is both the strategy and execution source, use
`--reference mt5-history`. This mode proves that quotes observed through the live
endpoint reappear byte-for-byte in the terminal's raw tick history.

## How to run

The audit talks to the same `mt5-gateway` the daemon uses, so it runs inside
the prod `qkt` container:

```sh
ssh root@<prod-host>
docker exec qkt qkt audit-ticks \
    --symbol XAUUSD \
    --duration 600 \
    --mt5-profile exness \
    --reference mt5-history
```

Flags:

- `--symbol` — the TV / strategy-facing symbol. A source prefix is stripped before
  resolving the MT5 side through the profile's `symbolPolicy`.
- `--mt5-symbol` — optional MT5-side base symbol when it differs from the suffix of
  `--symbol` (for example `--symbol OANDA:XAUUSD --mt5-symbol XAUUSD`).
- `--duration` — sample window in seconds. 600 s (10 min) gives a few
  thousand samples without saturating the gateway poller.
- `--mt5-profile` — broker profile from `qkt.config.yaml`. `exness` matches
  the broker hedge-straddle trades against.
- `--poll-ms` — MT5 poll cadence (default 250 ms). Leave default.
- `--reference` — `tradingview` (default) or `mt5-history` for a single-source
  production path.
- `--settle-ms` — wait before reading MT5 history (default 15000 ms). This avoids
  comparing a just-arrived quote before the terminal commits it to history.
- `--json` — emit a single-line JSON result to stdout instead of the human table.
- `--out <path>` — also persist the JSON to a file (regardless of `--json`). Parent dirs are created. Lets you skip the stdout-piping dance below.

### When to run

Run during liquid market hours so both feeds are active. Avoid:

- The 22:00–23:00 UTC daily MT5 server break.
- Friday 22:00 UTC → Sunday 22:00 UTC (XAUUSD venue closed).
- The 60 s either side of high-impact news, which produces drift that
  reflects the news, not the feeds.

A calm midday window in London or early NY is the most informative baseline.

## What the result means

`absDiff` is `|tvPrice - mt5Mid|` per sample. Report fields:

- `samples` — sample count
- `mean abs diff` — average drift across samples
- `median abs diff` — median, less sensitive to spikes
- `p95 abs diff` — 95th percentile, the "tail" drift
- `max abs diff` — worst sample in the window

For XAUUSD a healthy drift is typically <0.05 USD (≈ 5 pips of gold). Anything
in the >0.5 USD range during calm hours indicates a feed mismatch a strategy
should not be authored against.

## Recording results

Use `--out` to persist the run as JSON in one step:

```sh
ssh root@<prod-host> 'docker exec qkt qkt audit-ticks \
    --symbol XAUUSD --duration 600 --mt5-profile exness \
    --out "/var/lib/qkt/audits/XAUUSD-$(date -u +%Y%m%d-%H%M).json"'
```

Then `cat` the file and append a row to the table below — date in UTC, fields
straight off the JSON, `notes` for anything contextual (news event nearby,
partial outage, unusual spread).

Each persisted file is a one-line JSON object like:

```json
{"symbol":"XAUUSD","samples":2350,"mean_abs_diff":"0.0312","median_abs_diff":"0.0290","p95_abs_diff":"0.0680","max_abs_diff":"0.1240"}
```

Keep the audit files under a persistent volume (`/var/lib/qkt/audits/` on the
prod host) so they survive container restarts and can be diffed across
multiple runs over time.

## Latest result

**Preliminary MT5 path-integrity run — not sufficient to close #54 (2026-07-12).**

A read-only bot1 run sampled `AUDUSDm` for 120 seconds at 250 ms during the quiet
Sunday-open window. It observed 15 new in-window venue ticks; all 15 appeared in
`copy_ticks_range` with exact `time_msc`, bid, and ask values. Five M1 bars rebuilt
from 37 raw ticks matched the gateway's bid OHLC exactly. The initial live snapshot
predated the requested history window and was correctly excluded from the result.

The implemented CLI mode was then exercised through a local SSH tunnel for 30 seconds:
6 unique in-window ticks, 6 raw-history ticks, 6 exact timestamp and bid/ask matches,
zero mismatches, zero missing or invalid ticks, and `passed=true`. Quote-age p95 was 9022 ms,
consistent with the low tick rate in this window rather than transport delay.

This proves that the live, raw-history, and M1 paths agree, but 15 ticks in two minutes
is not a representative liquid-hours quality bound. A London or early-New-York run is
still required before closing the issue.

An isolated `XAUUSDm` validation on 2026-07-13 also established the settlement bound used
by the operator command. With the former 5000 ms default, 20 of 21 live ticks matched and
one had not appeared in history when queried. Repeating with 15000 ms produced 74 of 74
exact timestamp and bid/ask matches. The default is therefore 15000 ms; persisted artifacts
record `duration_seconds`, `poll_ms`, and `settle_ms` so this parameter is reviewable.

### Previous TradingView attempt (2026-06-03)

Attempting the live audit against the prod `qkt` container surfaced three things:

1. **TradingView blocks the prod VPS IP.** `curl https://data.tradingview.com/` from the
   prod host returns `(52) Empty reply from server` (TCP/TLS connects, then TV drops it), and
   the anonymous WebSocket gets a ping/pong timeout with zero ticks — a datacenter-IP block.
   The audit captures `no samples` because the TV side never delivers.
2. **This deployment doesn't use TradingView.** Prod config is `source: local`: every strategy
   trades ticks *and* orders through the exness `mt5-gateway`. If authoring/backtesting also run
   on MT5 bars (`qkt fetch EXNESS:XAUUSD`), TradingView is in no part of the path — so the
   TV-vs-MT5 drift this tool measures is not a risk the current setup actually has.
3. **The command could not bridge the two symbol conventions.** `--symbol XAUUSD` failed TV's
   required `EXCHANGE:SYMBOL` form; the TV side needs `OANDA:XAUUSD`, but `audit-ticks` reuses the
   one `--symbol` for the MT5 side too. The command now strips source prefixes and accepts an
   explicit `--mt5-symbol`; TradingView comparison still needs an authenticated TV token and a
   non-datacenter IP for TV to serve the feed.

If a strategy is ever authored or backtested against a TradingView feed it does not also execute
on, run the cross-source mode from a host TradingView will serve.

| date | symbol | duration | samples | mean | median | p95 | max | notes |
|------|--------|----------|---------|------|--------|-----|-----|-------|
|      |        |          |         |      |        |     |     |       |
