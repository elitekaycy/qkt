# Hub streams (`HUB:`)

A `HUB:` stream binds a dataset from a [qkt-data-hub](https://github.com/elitekaycy/qkt-data-hub) store: macro releases, economic-calendar events, hub health, and any other dataset the hub publishes. Its fields read like candle fields, in backtest and live, and each value becomes visible at the instant the hub knew it.

The engine only reads the store. Fetching and deriving datasets is the hub's job; qkt has no provider code.

## Declaring a hub stream

<!-- qkt-doc: grammar -->
```qkt
<alias> = HUB:<dataset> EVERY <window>
<alias> = HUB:<dataset>/<field> EVERY <window>
```

```qkt
STRATEGY hub_gate VERSION 1
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m,
    cpi  = HUB:macro.us.cpi EVERY 1d,
    cal  = HUB:cal.high_impact.USD EVERY 1d,
    hub  = HUB:hub.health EVERY 1m
RULES
    -- Flatten when the hub has gone quiet.
    WHEN NOW.epoch_ms - hub.last_heartbeat_at > 900000 AND POSITION.gold != 0
    THEN CLOSE gold

    -- Stay out for 30 minutes before a high-impact USD release.
    WHEN cal.next_high_at - NOW.epoch_ms < 1800000 AND POSITION.gold != 0
    THEN CLOSE gold

    WHEN cpi.surprise_z > 1.0 AND POSITION.gold = 0
    THEN SELL gold SIZING 0.01
```

- `<dataset>` is the hub dataset name, including any scope (`cal.high_impact.USD`, `macro.us.cpi`).
- `alias.<field>` reads a typed field of the latest record. A single-field dataset names its field `value`, so `alias.value` works.
- Enum fields arrive as ordinals. String fields are not bound.
- A hub alias is read-only: `BUY`, `SELL`, `CLOSE` and the other order actions on it are compile errors.

Each field the strategy reads becomes its own hidden stream (`cal/surprise = HUB:cal.high_impact.USD/surprise`), so indicators, lookback and warmup work on a field exactly as on a price: `ema(cpi.surprise_z, 3)` and `cpi.surprise_z[1]` are valid. Fields the strategy never reads cost nothing.

## When a value becomes visible

A record's field becomes visible at its `known_at`, the instant the hub recorded it, never at the period it describes (`effective_at`). In a backtest the value is merged into the tick stream at `known_at`; when a hub record and a market tick share a millisecond, the market tick is processed first, so a fact is seen on the next evaluation. Live, the value is visible when the hub's journal line is read, typically well under a second after `known_at`.

`min_lag_ms` adds a further delay to every record, the same in both modes, when you want to model processing time.

## Configuring the store

```yaml
hub:
  root: /data/qkt-hub        # snapshot and journal directory, read-only
  min_lag_ms: 0              # extra visibility delay applied to every record
  refuse_derived: true       # refuse records the hub marks as derived
  stale_after_ms: 900000     # heartbeat age after which the hub is reported stale
```

Without a `hub:` block, set `--hub-root` or the `QKT_HUB_ROOT` environment variable. Unknown keys in the block are errors, so a typo cannot silently disable a safety setting.

- **Backtest** reads the dataset's snapshot files, verifies each file's SHA-256 against the store manifest, and refuses to run on a mismatch. Every declared field is checked against the manifest before the first tick.
- **Live** tails the dataset's journal on its own thread and emits one value per field as records are appended. A stale hub does not stop the market feed; gate on `HUB:hub.health` as above.
- A strategy with no `HUB:` stream is unaffected: no hub source is constructed.

A hub dataset supersedes the older `MACRO:` series prefix for macro data.
