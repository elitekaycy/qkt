# Broker integration

How qkt routes orders to brokers and what the contract is between them.

## The `Broker` interface

```kotlin
interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>
    fun supports(symbol: String): Boolean
    fun submit(request: OrderRequest): SubmitAck
    fun cancel(orderId: String)
    fun modify(orderId: String, changes: OrderModification): SubmitAck
}
```

Every broker (Paper, Bybit, MT5) implements this. The CompositeBroker routes by symbol or by DSL stream label.

## Capability matrix

| Order type | PaperBroker | MT5Broker (v1) | BybitBroker | Else (engine-managed) |
|---|---|---|---|---|
| Market | ✅ | ✅ | ✅ | — |
| Limit | ✅ | engine | ✅ | engine fallback |
| Stop | ✅ | engine | ✅ | engine fallback |
| StopLimit | ✅ | engine | ✅ | engine fallback |
| Bracket | ✅ | ✅ | ✅ | — |
| TrailingStop | engine | engine | engine | engine fallback |
| OCO / OTO | engine | engine | engine | engine fallback |
| ScaleOut / TimeExit | engine | engine | engine | engine fallback |
| Stack (Phase 13a) | engine | engine | engine | engine-managed pyramiding |

**Engine-managed** = qkt holds the trigger logic and forwards Market entries to the broker when the trigger fires. Same effect, different ownership.

## DSL routing

Stream label = profile name = venue identity:

```qkt
SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m
```

`EXNESS:` resolves to the profile named `exness` in the broker registry, regardless of what protocol it uses today (MT5) or tomorrow (REST, native SDK). The DSL doesn't know about MT5.

The profile is configured in `qkt.config.yaml`:

```yaml title="qkt.config.yaml"
brokers:
  exness:                          # this name becomes the EXNESS: prefix
    type: mt5                      # protocol — implementation detail
    extends: exness                # inherits built-in suffix + tz settings
    gateway_url: http://localhost:5001
    magic: 4242
```

When the DSL sees `EXNESS:EURUSD`, it looks up `exness` in this registry and routes orders through whatever broker type is configured. Change `type: mt5` to `type: native-exness` tomorrow and **the strategy file doesn't change** — only the config does. This is the principle: **venue identity in the DSL, protocol detail in the config**.

## MT5 specifics

### Symbol translation

```text
qkt symbol → alias (if any) → + suffix
"EURUSD" → "EURUSD"           + "m"     → "EURUSDm"   (Exness)
"NAS100" → "USTEC"            + "m"     → "USTECm"    (Exness)
"EURUSD" → "EURUSD"           + ".raw"  → "EURUSD.raw" (ICMarkets)
```

Owned by `MT5Symbol`. Round-trip property: `toQkt(toBroker(s)) == s` for all aliased symbols.

### Magic

Each profile has a unique `magic` integer that tags every order it places. Position pollers filter by magic so multiple profiles on the same broker don't conflate.

### Position reconciliation

On daemon startup, `MT5StateRecovery` snapshots open positions filtered by magic and emits `BrokerEvent.PositionReconciled` per position. Qkt's `PositionTracker` resets to broker truth — strategies don't double-place after a daemon restart.

### Position polling

`MT5PositionPoller` runs at `pollIntervalMs` (default 1000ms) per profile. Diffs the current snapshot against the previous; emits `BrokerEvent.OrderFilled` for each disappeared ticket (broker-side SL/TP fired). Approximate close price = last known position price; future enhancement queries deal history for exact.

## Cross-broker same-symbol

**Not supported in v1.** Two profiles handling `EURUSD` in one strategy would conflate at `PositionTracker` (keys by symbol only). Workaround: run as two separate qkt deployments. Future v2 refactors `CompositeBroker` + `PositionTracker` + `BrokerEvent` to key on `(brokerName, symbol)`.

## See also

- [Reference: config schema](../reference/config-schema.md) — `qkt.config.yaml` `brokers:` section
- [Phase 17 changelog](../phases/index.md) — MT5 broker shipped
- [Phase 18 changelog](../phases/index.md) — `LiveSession` typed dispatch
