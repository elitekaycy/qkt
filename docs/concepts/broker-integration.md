# Broker integration

How qkt connects to the places it trades, and what each part is responsible for.

## The model

Futures made "broker" ambiguous, so qkt names each real thing separately.

| Real-world thing | Examples | In qkt |
|---|---|---|
| The technology you connect through | MetaTrader 5 (via mt5-gateway), Bybit v5 API | a **connector** — `com.qkt.connector.<type>` |
| What that technology can trade | CFDs, spot, perpetual swaps, dated futures | `ProductType` |
| One login at a broker, exchange or prop firm | an Exness demo, a prop-firm account | a **trading account** — one `brokers:` entry |
| A strategy's channel for orders on that account | market, limit, stop, bracket orders | an order-entry session (the `Broker` interface) |

One connector serves many accounts: the same MT5 connector opens an Exness demo and a prop-firm
account; each is just a `brokers:` entry with its own login.

## The contracts

Everything a connector must provide is defined in `com.qkt.connectivity`:

- **`Connector`** — one per connector type. `open(accounts, context)` receives every account of
  its type at once, so accounts on the same gateway or credentials can share a connection. It
  performs no network I/O.
- **`TradingAccount`** — one per `brokers:` entry:
    - `verify()` connects and checks the venue reports the account the config expects; the daemon
      refuses to start on any mismatch;
    - `orderEntry` creates each strategy's order-entry session (`Broker`);
    - `marketData` is the account's own price feed, if it has one;
    - `tradingHours` says when each symbol trades.
- **`ConnectorContext`** — what qkt hands a connector: environment, credential resolution,
  clock, state directory. Connectors never read globals.
- **`AccountDirectory`** — every configured account, looked up by name or by the prefix a
  strategy symbol carries.

An order-entry session may also offer optional abilities, each a small interface in
`com.qkt.broker`: `InstrumentProvider` (the venue's contract specs), `ServerTimeZoneProvider`
(the server clock `SCHEDULE … BROKER` uses), `TicketAttributionProvider` (positions found open at
startup, with their owning strategy). The live session asks for an ability, never for a
connector.

Nothing outside a connector's package may name it; `ConnectivityArchitectureTest` fails the build
if core code does, if a connector reaches above the shared model, or if a connector package is not
registered as a service.

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

Every order-entry session (Paper, Bybit, MT5) implements this. The CompositeBroker routes by symbol
or by DSL stream label.

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

Stream label = account name:

```qkt
SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m
```

`EXNESS:` resolves to the account named `exness`, whatever connector opens it. The DSL does not
know about MT5.

```yaml title="qkt.config.yaml"
brokers:
  exness:                          # this name becomes the EXNESS: prefix
    type: mt5                      # the connector — an implementation detail
    extends: exness                # inherits built-in suffix + tz settings
    gateway_url: http://localhost:5001
    magic: 4242
```

When the DSL sees `EXNESS:EURUSD`, the account directory finds `exness` and routes orders through
its connector. Move the account to another connector tomorrow and **the strategy file doesn't
change** — only the config does: venue identity in the DSL, connector detail in the config.

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

Live quotes use the independent `tickPollIntervalMs` cadence. Configuration exposes these as
`poll_interval_ms` and `tick_poll_interval_ms`. Keeping them separate lets a daemon reduce
terminal reconciliation load without silently slowing strategy market data. A legacy profile
that sets only `poll_interval_ms` retains the old coupled behavior; set both keys to opt into
independent tuning.

## Cross-broker same-symbol

**Not supported in v1.** Two profiles handling `EURUSD` in one strategy would conflate at `PositionTracker` (keys by symbol only). Workaround: run as two separate qkt deployments. Future v2 refactors `CompositeBroker` + `PositionTracker` + `BrokerEvent` to key on `(brokerName, symbol)`.

## See also

- [Reference: config schema](../reference/config-schema.md) — `qkt.config.yaml` `brokers:` section
- [Phase 17 changelog](../phases/index.md) — MT5 broker shipped
- [Phase 18 changelog](../phases/index.md) — `LiveSession` typed dispatch
