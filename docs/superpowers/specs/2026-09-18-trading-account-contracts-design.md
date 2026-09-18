# Trading account contracts — design

**Date:** 2026-09-18
**Status:** approved for implementation (Step 1a)
**Research:** [venue plugin architecture](../../research/2026-09-18-venue-plugin-architecture.md)

## Goal

Every trading account qkt connects to — a CFD account on an MT5 broker, a crypto account on an
exchange, and next a futures account through Rithmic — is configured, opened, verified and used
through **one set of contracts**. Everything specific to a connector lives in that connector's
package. Adding a connector touches only its own package and one registration line; core code
never learns its name.

Step 1a delivers the contracts, moves the two existing connectors (MT5, Bybit) behind them, and
rewires the live path to use them. **No trading behaviour changes for any MT5 deployment.**

## The real-world model and its names

Futures made the one-word "broker" ambiguous, so the contracts name each real thing separately.

| Real-world thing | Examples | Contract name |
|---|---|---|
| The technology you connect through | MetaTrader 5 (via mt5-gateway), Bybit v5 API, Rithmic R\|Protocol | `Connector` |
| What that technology can trade | CFDs, spot, perpetual swaps, dated futures | `ProductType` (`CFD`, `SPOT`, `PERPETUAL`, `FUTURE`) |
| One login at a broker, exchange or prop firm | The5ers HS 50k, Exness demo 436804390, AMP demo | `TradingAccount` |
| That account's section in `qkt.config.yaml` | `brokers.prop_s01:` | `AccountConfig` |
| What the venue reports about the login when you connect | login, server, live/demo, currency, leverage | `AccountProfile` |
| Live money, practice money, or a competition | MT5 trade modes REAL / DEMO / CONTEST | `AccountType` (`LIVE`, `DEMO`, `CONTEST`, `UNKNOWN`) |
| Checking you are logged into the account you meant | expected login/server/mode/currency/leverage/margin mode | `TradingAccount.verify()` |
| A strategy's channel for sending and managing orders | the engine's existing order-entry session | `Broker` (unchanged, see below) — created by `TradingAccount.orderEntry` |
| Prices from the account's own feed | MT5 ticks and bars, Bybit public WebSocket | `TradingAccount.marketData` |
| When each symbol trades | FX week, crypto 24/7, CME with a daily halt | `TradingAccount.tradingHours` (`SymbolCalendars`) |
| What qkt hands a connector | state directory, environment, secret resolution, clock | `ConnectorContext` |
| A password or API key | Bybit API secret, a gateway token | `Secret` (prints as `***`) |
| Every connector qkt can use | built-in MT5 and Bybit; Rithmic later | `ConnectorRegistry` |
| Every account configured on this daemon | all `brokers:` entries | `AccountDirectory` |

The engine-facing per-strategy interface keeps its name `Broker` in this step. It is referenced
147 times across 49 files; renaming it is a separate mechanical change and would bury this one.
Its KDoc is updated to say what it is: *the order-entry session a strategy uses on one account*.

Engine-facing optional abilities follow the existing `MarginLevelProvider` idiom — a small
interface a session's `Broker` may also implement:

| Ability | Interface | Who implements it now |
|---|---|---|
| Contract specs for the account's symbols | `InstrumentProvider` | MT5 |
| The broker server's time zone (`SCHEDULE … BROKER`) | `ServerTimeZoneProvider` | MT5 |
| Tickets recovered at startup, attributed to strategies | `TicketAttributionProvider` | MT5 |

`LiveSession` asks for an ability, never for a connector.

## Contracts

Package `com.qkt.connectivity`.

```kotlin
/** A technology qkt can trade through. One implementation per connector type. */
interface Connector {
    val spec: ConnectorSpec
    /** Opens every configured account of this type at once, so accounts can share connections. No network I/O. */
    fun open(accounts: List<AccountConfig>, context: ConnectorContext): List<TradingAccount>
}

data class ConnectorSpec(
    val type: String,                   // the `type:` value in config: "mt5", "bybit"
    val displayName: String,            // "MetaTrader 5", "Bybit"
    val productTypes: Set<ProductType>,
)

enum class ProductType { CFD, SPOT, PERPETUAL, FUTURE }

/** One login at one broker, exchange or prop firm. */
interface TradingAccount : AutoCloseable {
    val config: AccountConfig
    /** The strategy symbol prefix this account serves, e.g. "PROP_S01:". */
    val symbolPrefix: String get() = config.symbolPrefix
    /** Checks the venue reports the account the config expects. Throws on any mismatch or when unreachable. */
    fun verify(): AccountProfile
    /** Creates each strategy's order-entry session on this account. */
    val orderEntry: BrokerFactory
    /** Prices from this account's own feed; null when the connector does not supply market data. */
    val marketData: MarketSource?
    val tradingHours: SymbolCalendars
}

data class AccountConfig(
    val name: String,                                     // "prop_s01"
    val type: String,                                     // "mt5"
    val settings: Map<String, String>,                    // flat scalar fields, exactly as today
    val tradingHours: List<Pair<String, String>> = emptyList(),      // brokers.<name>.calendars
    val symbolAliases: Map<String, String> = emptyMap(),             // brokers.<name>.aliases
    val disabledOrderTypes: List<String> = emptyList(),              // brokers.<name>.capability_restrictions
    val instrumentOverrides: Map<String, Map<String, String>> = emptyMap(),
) { val symbolPrefix: String get() = "${name.uppercase()}:" }

data class AccountProfile(
    val accountName: String,
    val accountId: String,        // String: MT5 logins are numbers, Rithmic ids are not
    val server: String,
    val type: AccountType,
    val currency: String?,
    val leverage: Int?,
    val description: String,      // the operator line for logs and notifications, owned by the connector
)

enum class AccountType { LIVE, DEMO, CONTEST, UNKNOWN }

class ConnectorContext(
    val stateRoot: Path?,          // null for one-shot commands that keep no state
    val env: Map<String, String>,
    val secrets: SecretResolver,
    val clock: Clock,
)

@JvmInline value class Secret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "Secret(***)"
}
```

`SecretResolver.resolve(account, field)` resolves, highest first:
1. env `QKT_BROKER_<NAME>_<FIELD>` (the override convention MT5 profiles already use);
2. the config value: `env:VAR`, `file:/path`, `${VAR}`, or a literal.

`ConnectorRegistry` discovers connectors with `java.util.ServiceLoader` from
`META-INF/services/com.qkt.connectivity.Connector` — the same mechanism Step 2 uses for plugin
JARs, so moving a connector into its own JAR later changes no consumer.

`AccountDirectory.open(accounts, registry, context)`:
- fails closed on a missing or unknown `type`, naming the known types;
- groups accounts by type, calls each connector's `open` once, keeps config order;
- exposes `accounts`, `byName`, `orderEntry()` (`Map<String, BrokerFactory>` keyed by lowercase
  name — the shape `LiveSession` already takes), `marketDataRoutes()` (prefix routes in config
  order), `tradingHoursFor(qktSymbol)`, `verifyAll()`, `close()`.

## File structure

Everything a connector needs moves into its package; nothing about it stays elsewhere.

```
com/qkt/connectivity/                 contracts + host runtime (core owns)
  Connector.kt  ConnectorSpec.kt  ProductType.kt
  TradingAccount.kt  AccountConfig.kt  AccountProfile.kt  AccountType.kt
  ConnectorContext.kt  Secret.kt  SecretResolver.kt
  ConnectorRegistry.kt  AccountDirectory.kt
com/qkt/connector/mt5/                was broker/mt5 + instrument/MT5*InstrumentRegistry
  Mt5Connector.kt                     new: the entry point
  marketdata/                         was marketdata/live/mt5
com/qkt/connector/bybit/              was broker/bybit (spot/, linear/ kept)
  BybitConnector.kt                   new
  marketdata/                         was marketdata/live/bybit
com/qkt/common/SymbolCalendars.kt     was broker/mt5 — per-symbol trading hours are not MT5's
com/qkt/broker/BrokerFactory.kt       was app/ — connectors produce it and must not import app
com/qkt/broker/InstrumentProvider.kt, ServerTimeZoneProvider.kt, TicketAttributionProvider.kt
```

Connectors may use the engine's shared model packages (`broker`, `bus`, `common`, `events`,
`execution`, `positions`, `instrument`, `marketdata`, `accounting`, `persistence`) and nothing
above them. Verified on 2026-09-18: MT5 and Bybit code references `app`/`cli` only in KDoc.

Tests move with their subjects.

## Wiring

- **Daemon** builds one `AccountDirectory` from `cfg.brokers` and gets from it: order-entry
  factories, market-data routes, trading hours, verified account profiles (fail closed, exit
  `USER_ERROR` exactly as today), startup log lines and the `DaemonStarted` account list. The
  MT5 read caches, transport journals and client sharing move into `Mt5Connector`; the Bybit
  client moves into `BybitConnector`. The directory is closed on shutdown.
- **`qkt run`** opens the directory for market data only; it does not verify accounts (it never did).
- **`MarketSourceFactory.composite`** takes the directory's routes instead of MT5 profiles. Hub,
  macro and fallback routes are unchanged. The Bybit environment switch is removed.
- **`LiveSession`** replaces its three `filterIsInstance<MT5Broker>()` calls with
  `InstrumentProvider`, `TicketAttributionProvider` and `ServerTimeZoneProvider`.
- **`liveCalendarFor`** asks the directory; its hardcoded Bybit branch is removed (Bybit accounts
  declare crypto hours).

MT5 connection sharing is preserved exactly: accounts on the same gateway and credentials share
one `MT5Client` and read cache, and accounts with the same market-data identity share one feed
(`Mt5MarketDataIdentity`), because `Mt5Connector.open` sees all MT5 accounts together.

## Config

MT5 entries are unchanged. Bybit becomes an ordinary entry; the account name is the prefix, so
existing `BYBIT_SPOT:` / `BYBIT_LINEAR:` strategies keep working:

```yaml
brokers:
  bybit_linear:
    type: bybit
    category: linear              # spot | linear
    api_key: env:BYBIT_API_KEY
    api_secret: env:BYBIT_API_SECRET
    testnet: "true"
```

Accounts with identical credentials and testnet flag share one Bybit client, built on first use.

The Bybit brokers hardcode the `BYBIT_SPOT:` / `BYBIT_LINEAR:` prefixes internally, so in this
step a Bybit entry must be named after its category (`bybit_spot`, `bybit_linear`); any other name
is refused with a message saying so. Configurable Bybit prefixes (several Bybit logins on one
daemon) are a Bybit-internal change for later.

## Behaviour changes (intended, all outside MT5 trading)

1. Bybit is enabled by a `type: bybit` entry, never by `BYBIT_API_KEY` alone, and its private
   connection is checked by `verify()` at daemon start — a failure now stops startup like an MT5
   mismatch does, instead of logging a warning and trading on a dead connection. No deployment
   uses Bybit today (checked bot1, bot2, local labs on 2026-09-18).
2. A `brokers:` entry with a missing or unknown `type` is a startup error. Every deployed config
   sets `type: mt5` (checked on the same date).
3. Startup logs and `DaemonStarted` list every verified account, not only MT5 ones. The labels are
   connector-neutral — `[INFO] broker accounts loaded: …` and `[INFO] account: …` replace
   `mt5 broker profiles loaded:` / `mt5 account:` — while each account's description is
   byte-identical (the connector owns it). Load and preflight errors say "broker account" instead
   of "MT5 profile", with the same exit code. The "live account in non-production mode" warning
   covers any `AccountType.LIVE` account and names it. No repository parses these lines (checked
   2026-09-18).
4. `qkt run` and `qkt bot` live sessions open accounts through the directory too; `qkt bot` live
   now shares one client and read cache per account instead of one client per strategy session.
5. MT5 `api_key` also accepts `env:` and `file:` references, like every connector's credentials.

## Enforcement

`ConnectivityArchitectureTest` reads every main source file (comments stripped) and fails when:
1. a file outside `com.qkt.connector.<x>` references `com.qkt.connector.<x>`;
2. a connector references `com.qkt.app`, `cli`, `risk`, `observe`, `dsl`, `backtest` or another connector;
3. `com.qkt.connectivity` references any connector.

MT5-only operator tools not migrated in this step are an explicit allow-list in that test —
the exact files that reference MT5 code outside KDoc once the live path is rewired:
`trade/BotGateway.kt`, `trade/BotGatewayResult.kt`, `cli/bot/BotSessionCommand.kt`,
`cli/FetchCommand.kt`, `cli/InstrumentsCommand.kt`, `cli/PreflightCommand.kt`,
`cli/BrokersCommand.kt`, `cli/AuditTicksCommand.kt`, `cli/Mt5FeedAudit.kt`, `tools/parity/*`.
The list can only shrink: the test also fails when an allow-listed file no longer needs its entry.

## Testing and parity proof

- The existing suites stay green unchanged in assertion: `BacktestLiveParityTest`,
  `MT5BrokerSimulatorTest`, `MT5GoldenVerifierTest`, `MT5DaemonE2ETest`, portfolio E2E.
- Existing wiring tests (`MarketSourceFactoryTest`, `DaemonCommandSourceWiringTest`,
  `DaemonCommandCalendarTest`) are ported to the directory with the same assertions; the Bybit
  ones assert the config-driven behaviour instead of the env switch.
- New focused tests: `SecretResolver`, `ConnectorRegistry`, `AccountDirectory` (unknown type,
  missing type, order, routing, close), `Mt5Connector` (profile parity with the old loader,
  shared client and feed grouping, verify, description), `BybitConnector` (config, secrets,
  client sharing, prefixes), `LiveSession` ability lookups.
- `ConnectivityArchitectureTest`.
- Full `./gradlew build` and `test` green before and after each commit.

## Out of scope (next steps)

- **1b — account state:** per-account `AccountState` (summary, open positions, working orders,
  fills) replacing the read methods on `Broker`; failed reads throw instead of returning empty;
  one poll per account fanned out to strategies; `Fill` with typed costs replacing `BrokerDeal`;
  string account ids in insights with MT5 keys unchanged.
- **1c — financing and products:** `FinancingCharge` (swap, funding), `InstrumentMeta.productType`
  and futures fields, Bybit typed costs.
- Migrating the MT5-only operator tools; renaming `Broker`.
- Step 2: plugin JARs.
