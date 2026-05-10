# Phase 17 — MT5 Broker (multi-profile via mt5-gateway)

**Released:** 2026-05-10
**Version:** 0.19.0

## Summary

Phase 17 ships a profile-driven MT5 broker that talks to per-broker `mt5-gateway` HTTP services. Built-in defaults cover Exness, ICMarkets, FTMO, and Pepperstone; end-users override or extend them via `qkt.config.yaml`. Capabilities are protocol-level (`MT5Protocol.capabilities = [MARKET, BRACKET]`); profiles can only opt-out. The same architecture supports many MT5 venues simultaneously — a single qkt strategy can trade on Exness for one symbol and ICMarkets for another by referencing distinct profile names. Tick source remains TradingView (Phase 7c); MT5 handles execution only. The broker is integration-tested via MockWebServer; live deployment requires a follow-on LiveSession refactor to route per-broker dispatch (deferred).

## What's new

- `com.qkt.broker.mt5.MT5Protocol` — protocol-level constant declaring native capabilities (`MARKET`, `BRACKET`).
- `MT5BrokerProfile` — policy bundle: gateway URL, symbol policy, server TZ offset, magic, instrument overrides, poll/timeout/retry, deviation, optional capability restrictions. Capabilities derived from `MT5Protocol`, not declared per profile.
- `SymbolPolicy` (suffix + aliases) and `InstrumentSpec` (min volume, point size, etc.).
- `MT5DefaultProfiles` — built-in profiles for `exness`, `icmarkets`, `ftmo`, `pepperstone`. Stub defaults; operators override per install.
- `MT5Symbol` — qkt ↔ broker symbol translation with suffix and alias rules. Round-trip property: `toQkt(toBroker(s)) == s`.
- `MT5OrderTranslator` — qkt `OrderRequest.Market` and `OrderRequest.Bracket` → `MT5OrderRequest`. Other variants throw (caller falls back to engine-managed paths).
- `MT5Client` — OkHttp wrapper with retry on GET, no retry on POST `/order` (duplicate placement worse than transient failure), TZ normalization on returned timestamps.
- `MT5StateRecovery` — startup: snapshots open positions, emits `BrokerEvent.PositionReconciled` per position so qkt's tracker matches MT5 truth.
- `MT5PositionPoller` — daemon thread, ~1Hz default; diffs positions to detect closes, emits `BrokerEvent.OrderFilled` for SL/TP-triggered exits.
- `MT5Broker` — implements `Broker`. Owns its `MT5Client` + poller + state recovery. Synchronous fill emission on `submit` for Market orders.
- `MT5BrokerProfileLoader` — resolves `Config.brokers` map: built-in defaults → name-match partial overrides → `extends:` chains → env var hot-fixes (`QKT_BROKER_<NAME>_<FIELD>`). Validates magic uniqueness across resolved profiles.
- Daemon startup loads MT5 profiles at boot; logs them for visibility.
- `qkt brokers list` CLI subcommand prints resolved profiles with provenance.

## Migration from Phase 16

**No DSL changes.** Strategies declaring `BACKTEST:` / `BYBIT:` continue to work unchanged. New strategies can reference `EXNESS:` / `ICMARKETS:` / etc. once a profile is configured.

**No `Broker` interface changes.** `MT5Broker` slots into the existing dispatch.

**`Config.brokers` extension.** Phase 12a's `qkt.config.yaml` `brokers:` section gains semantic meaning for entries with `type: mt5`. Other entries (e.g., Bybit credentials) continue to be parsed by their respective broker modules.

**Live strategy → MT5 routing requires LiveSession refactor.** v1 surfaces profiles via `qkt brokers list` and loads them at daemon startup, but `LiveSession` still constructs `PaperBroker` only. Wiring the profile-keyed broker registry into `LiveSession`'s broker construction is a follow-on phase.

## Usage cookbook

### Override the default Exness profile (just change the gateway URL)

```yaml
# ./qkt.config.yaml
brokers:
  exness:
    type: mt5
    gateway_url: http://localhost:5005
```

Resolved profile = built-in `exness` defaults (`suffix: m`, NAS100→USTEC alias, TZ=2, `magic: 10001`) with `gatewayUrl` swapped.

### Multiple Exness accounts

```yaml
brokers:
  exness-personal:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5005
    magic: 10005

  exness-corporate:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5006
    magic: 10006
```

Both inherit Exness's symbol policy and TZ; only URL + magic differ. Strategies reference `EXNESS-PERSONAL:EURUSD` or `EXNESS-CORPORATE:XAUUSD`.

### Add a totally new MT5 broker

```yaml
brokers:
  myforex:
    type: mt5
    gateway_url: http://localhost:6000
    symbol_suffix: ".pro"
    server_tz_offset_hours: 3
    magic: 50001
```

### Env var override (prod hot-fix)

```bash
export QKT_BROKER_EXNESS_GATEWAY_URL=http://prod-gateway:5001
export QKT_BROKER_EXNESS_MAGIC=99001
qkt daemon
```

Env vars override YAML fields without editing the file.

### Inspect resolved profiles

```
$ qkt brokers list
NAME              KIND  GATEWAY                  SUFFIX  TZ  MAGIC
exness            mt5   http://localhost:5005    m       2   10001
exness-personal   mt5   http://localhost:5006    m       2   10005
icmarkets         mt5   http://localhost:5002    .raw    3   10002
myforex           mt5   http://localhost:6000    .pro    3   50001
```

`qkt brokers list --json` for tooling integration.

### Spinning up an mt5-gateway

The gateway runs in Docker with Wine + MT5. See `~/Desktop/personal/fxquant/mt5-gateway/README.md` for setup. Each broker = its own gateway container at its own port. Start the gateway, log in to MT5 via VNC, then point qkt at `http://localhost:<port>`.

## Testing patterns

`MT5Symbol` round-trip is unit-tested:

```kotlin
val s = MT5Symbol(MT5DefaultProfiles.exness.symbolPolicy)
for (q in listOf("EURUSD", "NAS100", "UKOIL")) {
    assertThat(s.toQkt(s.toBroker(q))).isEqualTo(q)
}
```

`MT5Client` is integration-tested via OkHttp's MockWebServer:

```kotlin
val server = MockWebServer().apply { start() }
server.enqueue(MockResponse().setBody("""{"result":{"retcode":10009,...}}"""))
val client = MT5Client(server.url("/").toString().trimEnd('/'), tzOffsetHours = 2, retryAttempts = 0)
val resp = client.placeOrder(MT5OrderRequest(...))
assertThat(resp.result.retcode).isEqualTo(10009)
```

`MT5Broker` end-to-end fills published to bus:

```kotlin
val captured = mutableListOf<BrokerEvent>()
bus.subscribe<BrokerEvent.OrderFilled> { captured.add(it) }
broker.submit(OrderRequest.Market(...))
assertThat(captured).hasSize(1)
```

## Known limitations

- **Live strategy → MT5 dispatch deferred.** `LiveSession` constructs `PaperBroker` only. Profiles load at daemon startup and surface via `qkt brokers list`, but live strategies still trade against PaperBroker. A LiveSession refactor accepting a profile-keyed broker registry is a follow-on.
- **Cross-broker same-symbol routing.** Two MT5 profiles trading `EURUSD` in one strategy would conflate at `PositionTracker` (keys by symbol only). Workaround: run as two separate qkt deployments. v2 refactors `CompositeBroker` + `PositionTracker` + `BrokerEvent` to key on `(brokerName, symbol)`.
- **Native broker capabilities = `[MARKET, BRACKET]` only.** Limit, Stop, StopLimit, IfTouched, OCO, OTO, ScaleOut, TimeExit, TrailingStop, Stack — all fall through to qkt's engine-managed paths (qkt holds the trigger, sends Market when triggered). Future versions extend `MT5Protocol.capabilities` and `MT5OrderTranslator`.
- **TradingView vs MT5 price drift.** Strategy decisions on TV prices, fills at MT5 prices. Spread + sub-second drift can flip whether stops trigger in backtest vs live. Strategies should set SL buffers wider than typical TV/MT5 spread on the symbol; deferred tick-feed accuracy audit will quantify.
- **1Hz position-poll latency.** Default poll interval = 1000ms. SL/TP triggers detected up to 1s after broker-side execution. Tighten via `poll_interval_ms` in profile; CPU cost negligible.
- **Approximate close prices on poller-detected exits.** Poller emits the last-known position price on disappearance, not the actual MT5 deal price. Future enhancement: query `/deal_history` for exact close.
- **Magic uniqueness within instance only.** If two qkt instances use the same magic against the same broker, their pollers see each other's positions. Doc says: "magic must be unique per (broker, qkt instance)". Future: derive magic from instance-id + profile.
- **No multi-account per single mt5-gateway.** Each profile = its own gateway container. mt5-gateway as it stands does not support multi-login natively.
- **No DST-aware TZ.** `serverTzOffsetHours` is a fixed integer. Brokers that switch GMT+2 ↔ GMT+3 across DST need manual config update at the boundary. Future: query gateway for current server time and compute offset dynamically.
- **`OrderManager.modify` not implemented.** SL/TP modifications after entry require a new method on `Broker` interface; future phase.

## References

- Spec: [`docs/superpowers/specs/2026-05-10-trading-engine-phase17-mt5-broker-design.md`](../superpowers/specs/2026-05-10-trading-engine-phase17-mt5-broker-design.md)
- Plan: [`docs/superpowers/plans/2026-05-10-trading-engine-phase17-mt5-broker.md`](../superpowers/plans/2026-05-10-trading-engine-phase17-mt5-broker.md)
- Reference TS impl: `~/Desktop/personal/fxquant/pa-quant/src/broker/mt5-client.ts`
- mt5-gateway service: `~/Desktop/personal/fxquant/mt5-gateway`
- Phase 12a config infrastructure: [`docs/phases/phase-12a-cli.md`](phase-12a-cli.md)
- Bybit broker (architectural pattern): `src/main/kotlin/com/qkt/broker/bybit/`
