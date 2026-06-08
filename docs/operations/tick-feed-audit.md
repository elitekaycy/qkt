# Tick-feed audit

`qkt audit-ticks` measures statistical drift between two tick sources for the
same symbol — typically TradingView (what a strategy *sees* during authoring)
vs the MT5 gateway (what the strategy *fills against* during live trading).
Run this before committing capital so the drift bound is known.

## How to run

The audit talks to the same `mt5-gateway` the daemon uses, so it runs inside
the prod `qkt` container:

```sh
ssh root@<prod-host>
docker exec qkt qkt audit-ticks \
    --symbol XAUUSD \
    --duration 600 \
    --mt5-profile exness
```

Flags:

- `--symbol` — the TV / strategy-facing symbol. The MT5 side is resolved via
  the profile's `symbolPolicy`.
- `--duration` — sample window in seconds. 600 s (10 min) gives a few
  thousand samples without saturating the gateway poller.
- `--mt5-profile` — broker profile from `qkt.config.yaml`. `exness` matches
  the broker hedge-straddle trades against.
- `--poll-ms` — MT5 poll cadence (default 250 ms). Leave default.
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

**Not run — blocked, and not currently applicable to this deployment (2026-06-03, #54 parked).**

Attempting the live audit against the prod `qkt` container surfaced three things:

1. **TradingView blocks the prod VPS IP.** `curl https://data.tradingview.com/` from the
   prod host returns `(52) Empty reply from server` (TCP/TLS connects, then TV drops it), and
   the anonymous WebSocket gets a ping/pong timeout with zero ticks — a datacenter-IP block.
   The audit captures `no samples` because the TV side never delivers.
2. **This deployment doesn't use TradingView.** Prod config is `source: local`: every strategy
   trades ticks *and* orders through the exness `mt5-gateway`. If authoring/backtesting also run
   on MT5 bars (`qkt fetch EXNESS:XAUUSD`), TradingView is in no part of the path — so the
   TV-vs-MT5 drift this tool measures is not a risk the current setup actually has.
3. **The command can't bridge the two symbol conventions.** `--symbol XAUUSD` fails TV's
   required `EXCHANGE:SYMBOL` form; the TV side needs `OANDA:XAUUSD`, but `audit-ticks` reuses the
   one `--symbol` for the MT5 side too, and `MT5Symbol.toBroker` can't map an exchange-prefixed TV
   symbol to the broker symbol. A working audit needs *separate* TV/MT5 symbols (as the
   `runParityTicksXauusd` tool already takes), plus an authenticated TV token and a non-datacenter
   IP for TV to serve the feed.

When this becomes relevant again — i.e. if a strategy is ever authored or backtested against a
TradingView feed it does not also execute on — reopen #54, fix the symbol handling, and run from a
host TradingView will serve.

| date | symbol | duration | samples | mean | median | p95 | max | notes |
|------|--------|----------|---------|------|--------|-----|-----|-------|
|      |        |          |         |      |        |     |     |       |
