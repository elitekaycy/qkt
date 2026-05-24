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
- `--json` — emit a single-line JSON result instead of the human table.

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

Capture the run as JSON so the table row drops in cleanly:

```sh
ssh root@<prod-host> \
    "docker exec qkt qkt audit-ticks --symbol XAUUSD --duration 600 --mt5-profile exness --json" \
    | tee "/tmp/audit-XAUUSD-$(date -u +%Y%m%d-%H%M).json"
```

Then append a row to the table below — date in UTC, fields straight off the
JSON, `notes` for anything contextual (news event nearby, partial outage,
unusual spread).

## Latest result

_Pending — first live audit not yet run. See issue #54._

| date | symbol | duration | samples | mean | median | p95 | max | notes |
|------|--------|----------|---------|------|--------|-----|-----|-------|
|      |        |          |         |      |        |     |     |       |
