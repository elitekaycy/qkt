# SEQUENCE

`SEQUENCE` defines a deterministic multi-stage setup that advances on closed candles from one stream.
Use it when a trade needs ordered evidence, such as sweep -> reclaim -> momentum confirmation, before a rule can act.

```qkt
STRATEGY sweep_reclaim VERSION 1
SYMBOLS
    gold = BACKTEST:XAUUSD EVERY 1m

SEQUENCE sweep ON gold {
    STAGE swept: gold.low < lowest(gold.low, 20)[1]
    STAGE reclaimed WITHIN 30m: gold.close > lowest(gold.low, 20)[1]
    STAGE go WITHIN 15m: rsi(gold.close, 14) > 50
}

RULES
    WHEN SEQUENCE.sweep.complete
    THEN BUY gold SIZING 1
```

## Grammar

```qkt
SEQUENCE <name> ON <stream> {
    STAGE <stage_name> [WITHIN <duration>]: <condition>
    STAGE <stage_name> [WITHIN <duration>]: <condition>
}
```

- A sequence has exactly one `ON` stream.
- A sequence must declare 2 to 8 stages.
- Stage names must be unique within the sequence.
- `WITHIN` is measured from the previous stage fire time.
- A timeout resets the whole sequence, including snapshots.

## Evaluation

Stages are edge-triggered and strictly ordered. A later stage being true early does not skip the current stage, and if it remains true after the previous stage fires, it must produce a fresh false-to-true edge before advancing.

Sequences run during the same closed-candle evaluation pass as rules:

1. indicators, rolling snapshots, and aggregates update;
2. matching sequences advance;
3. rules evaluate;
4. `SEQUENCE.<name>.complete` pulses are cleared after the rule pass.

No worker thread or async path is introduced. Backtest and live DSL strategies both use the same `CandleHub` close path.

## Accessors

```qkt
SEQUENCE.<name>.stage
SEQUENCE.<name>.complete
SEQUENCE.<name>.<stage>.price
SEQUENCE.<name>.<stage>.time
```

- `stage` is numeric: `0` means idle, `1..n` means progress through the stage list.
- `complete` is a one-rule-pass boolean pulse when the final stage fires.
- `<stage>.price` is the `ON` stream close at the stage fire.
- `<stage>.time` is the `ON` stream candle close time in UTC epoch milliseconds.
- Missing stage snapshots read as `0` in numeric expressions.

## Persistence

Sequence runtime state is persisted with strategy state: current stage, per-stage snapshots, prior edge values, and the complete pulse. A restart resumes the same progress instead of treating an in-flight setup as new.
