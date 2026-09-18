# Trading Account Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put every trading account (MT5 CFDs, Bybit crypto; Rithmic futures next) behind one contract layer so a connector lives entirely in its own package and core never names it.

**Architecture:** New `com.qkt.connectivity` package holds the contracts (`Connector`, `TradingAccount`, `AccountConfig`, `AccountProfile`, `ConnectorContext`, `Secret`) and the host runtime (`ConnectorRegistry` via `ServiceLoader`, `AccountDirectory`). MT5 and Bybit move into `com.qkt.connector.mt5` / `com.qkt.connector.bybit` and implement `Connector`. The daemon, `qkt run`, market-data routing and `LiveSession` consume only the contracts; an architecture test enforces the dependency direction.

**Tech Stack:** Kotlin 2 / JVM 21, Gradle, JUnit 5, AssertJ, OkHttp MockWebServer, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-18-trading-account-contracts-design.md`

## Global Constraints

- Branch `refactor/venue-contracts` from `dev`; one concern per commit; Conventional Commit subjects only, lowercase, ≤70 chars, no body, no footer.
- No trading behaviour change for MT5: order entry, recovery, polling, market data sharing, verification failures and log lines stay identical.
- Intended behaviour changes are exactly the three in the spec's "Behaviour changes" section.
- Connectors may import only `broker`, `bus`, `common`, `events`, `execution`, `positions`, `instrument`, `marketdata`, `accounting`, `persistence`, `connectivity`; never `app`, `cli`, `risk`, `observe`, `dsl`, `backtest`, `trade`, or another connector.
- No new dependencies. No `!!`, no wildcard imports, KDoc on new public types, `BigDecimal` for money.
- Build command: `./gradlew <tasks> -Pkotlin.compiler.execution.strategy=daemon`. Baseline 2026-09-18: `./gradlew test` green in 2m21s.

---

### Task 1: Move connectors into their own packages

Pure relocation. No logic changes.

**Files:**
- Move: `src/{main,test}/kotlin/com/qkt/broker/mt5/**` → `.../connector/mt5/**`
- Move: `src/{main,test}/kotlin/com/qkt/marketdata/live/mt5/**` → `.../connector/mt5/marketdata/**`
- Move: `src/{main,test}/kotlin/com/qkt/instrument/{MT5InstrumentRegistry,MultiMT5InstrumentRegistry}*.kt` → `.../connector/mt5/`
- Move: `src/{main,test}/kotlin/com/qkt/broker/bybit/**` → `.../connector/bybit/**`
- Move: `src/{main,test}/kotlin/com/qkt/marketdata/live/bybit/**` → `.../connector/bybit/marketdata/**`
- Move: `connector/mt5/SymbolCalendars.kt` (+ test) → `common/SymbolCalendars.kt`
- Move: `app/BrokerFactory.kt` → `broker/BrokerFactory.kt`

**Interfaces:**
- Produces: packages `com.qkt.connector.mt5`, `com.qkt.connector.mt5.marketdata`, `com.qkt.connector.bybit(.spot|.linear|.marketdata)`, `com.qkt.common.SymbolCalendars`, `com.qkt.broker.BrokerFactory`.

- [ ] **Step 1: Relocate files with git**

```bash
set -e
for root in src/main/kotlin/com/qkt src/test/kotlin/com/qkt; do
  mkdir -p $root/connector/mt5/marketdata $root/connector/bybit/marketdata
  [ -d $root/broker/mt5 ] && git mv $root/broker/mt5/* $root/connector/mt5/
  [ -d $root/marketdata/live/mt5 ] && git mv $root/marketdata/live/mt5/* $root/connector/mt5/marketdata/
  [ -d $root/broker/bybit ] && git mv $root/broker/bybit/* $root/connector/bybit/
  [ -d $root/marketdata/live/bybit ] && git mv $root/marketdata/live/bybit/* $root/connector/bybit/marketdata/
  for f in MT5InstrumentRegistry MultiMT5InstrumentRegistry MT5InstrumentRegistryTest MultiMT5InstrumentRegistryTest; do
    [ -f $root/instrument/$f.kt ] && git mv $root/instrument/$f.kt $root/connector/mt5/
  done
  [ -f $root/connector/mt5/SymbolCalendars.kt ] && git mv $root/connector/mt5/SymbolCalendars.kt $root/common/
  [ -f $root/connector/mt5/SymbolCalendarsTest.kt ] && git mv $root/connector/mt5/SymbolCalendarsTest.kt $root/common/
done
git mv src/main/kotlin/com/qkt/app/BrokerFactory.kt src/main/kotlin/com/qkt/broker/BrokerFactory.kt
```

- [ ] **Step 2: Rewrite package names and references**

```bash
files=$(git ls-files 'src/*.kt')
sed -i -E \
  -e 's/com\.qkt\.marketdata\.live\.mt5/com.qkt.connector.mt5.marketdata/g' \
  -e 's/com\.qkt\.marketdata\.live\.bybit/com.qkt.connector.bybit.marketdata/g' \
  -e 's/com\.qkt\.broker\.mt5\.SymbolCalendars/com.qkt.common.SymbolCalendars/g' \
  -e 's/com\.qkt\.broker\.mt5/com.qkt.connector.mt5/g' \
  -e 's/com\.qkt\.broker\.bybit/com.qkt.connector.bybit/g' \
  -e 's/com\.qkt\.instrument\.(Multi)?MT5InstrumentRegistry/com.qkt.connector.mt5.\1MT5InstrumentRegistry/g' \
  -e 's/com\.qkt\.app\.BrokerFactory/com.qkt.broker.BrokerFactory/g' \
  $files
sed -i 's/^package com.qkt.connector.mt5$/package com.qkt.common/' src/main/kotlin/com/qkt/common/SymbolCalendars.kt src/test/kotlin/com/qkt/common/SymbolCalendarsTest.kt
sed -i 's/^package com.qkt.instrument$/package com.qkt.connector.mt5/' src/*/kotlin/com/qkt/connector/mt5/*MT5InstrumentRegistry*.kt
sed -i 's/^package com.qkt.app$/package com.qkt.broker/' src/main/kotlin/com/qkt/broker/BrokerFactory.kt
```

- [ ] **Step 3: Compile and add the imports that same-package references relied on**

Run: `./gradlew compileKotlin compileTestKotlin -Pkotlin.compiler.execution.strategy=daemon 2>&1 | grep '^e:' | head -60`

Expected: `Unresolved reference` errors only where a moved file used a class from its old package without an import (e.g. `MT5InstrumentRegistry` → `InstrumentRegistry`, `InstrumentMeta`; `SymbolCalendars` → `TradingCalendar`; `BrokerFactory` → `EventBus`, `Clock`, `MarketPriceTracker`, `PositionProvider`; files in `app`/`cli` that used `BrokerFactory` unqualified). Add each missing `import com.qkt.<pkg>.<Class>` line and repeat until the compile is clean. Do not change any code other than imports.

- [ ] **Step 4: Format and run the full suite**

Run: `./gradlew ktlintFormat test -Pkotlin.compiler.execution.strategy=daemon`
Expected: BUILD SUCCESSFUL, same test count as baseline.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -m "refactor(broker): move mt5 and bybit into connector packages"
```

---

### Task 2: The contracts

**Files:**
- Create: `src/main/kotlin/com/qkt/connectivity/Connector.kt`, `TradingAccount.kt`, `AccountConfig.kt`, `AccountProfile.kt`, `ConnectorContext.kt`, `Secret.kt`, `SecretResolver.kt`
- Test: `src/test/kotlin/com/qkt/connectivity/SecretResolverTest.kt`, `AccountConfigTest.kt`

**Interfaces:**
- Consumes: `com.qkt.broker.BrokerFactory`, `com.qkt.common.SymbolCalendars`, `com.qkt.common.Clock`, `com.qkt.marketdata.source.MarketSource`.
- Produces: everything below, exactly as written.

- [ ] **Step 1: Write the failing tests**

```kotlin
// src/test/kotlin/com/qkt/connectivity/SecretResolverTest.kt
package com.qkt.connectivity

import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SecretResolverTest {
    private fun account(vararg settings: Pair<String, String>) =
        AccountConfig(name = "bybit_linear", type = "bybit", settings = mapOf(*settings))

    @Test
    fun `env override beats the config value`() {
        val resolver = SecretResolver(env = mapOf("QKT_BROKER_BYBIT_LINEAR_API_KEY" to "from-override", "K" to "from-ref"))
        assertThat(resolver.resolve(account("api_key" to "env:K"), "api_key")?.reveal()).isEqualTo("from-override")
    }

    @Test
    fun `env, dollar and file references resolve`() {
        val file = Files.createTempFile("secret", ".txt").also { Files.writeString(it, "from-file\n") }
        val resolver = SecretResolver(env = mapOf("K" to "from-env"))
        assertThat(resolver.resolve(account("a" to "env:K"), "a")?.reveal()).isEqualTo("from-env")
        assertThat(resolver.resolve(account("a" to "\${K}"), "a")?.reveal()).isEqualTo("from-env")
        assertThat(resolver.resolve(account("a" to "file:$file"), "a")?.reveal()).isEqualTo("from-file")
        assertThat(resolver.resolve(account("a" to "literal"), "a")?.reveal()).isEqualTo("literal")
    }

    @Test
    fun `absent field is null and a missing reference is an error`() {
        val resolver = SecretResolver(env = emptyMap())
        assertThat(resolver.resolve(account(), "api_key")).isNull()
        assertThatThrownBy { resolver.resolve(account("api_key" to "env:NOPE"), "api_key") }
            .hasMessageContaining("bybit_linear.api_key")
            .hasMessageContaining("NOPE")
    }

    @Test
    fun `a secret never prints its value`() {
        assertThat(Secret("hunter2").toString()).isEqualTo("Secret(***)")
        assertThat("${Secret("hunter2")}").doesNotContain("hunter2")
    }
}
```

```kotlin
// src/test/kotlin/com/qkt/connectivity/AccountConfigTest.kt
package com.qkt.connectivity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccountConfigTest {
    @Test
    fun `symbol prefix is the upper-cased account name`() {
        assertThat(AccountConfig("prop_s01", "mt5", emptyMap()).symbolPrefix).isEqualTo("PROP_S01:")
    }

    @Test
    fun `setting reads a scalar field`() {
        val cfg = AccountConfig("a", "mt5", mapOf("gateway_url" to "http://gw"))
        assertThat(cfg.setting("gateway_url")).isEqualTo("http://gw")
        assertThat(cfg.setting("missing")).isNull()
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'com.qkt.connectivity.*' -Pkotlin.compiler.execution.strategy=daemon`
Expected: compilation FAIL, `Unresolved reference: SecretResolver`.

- [ ] **Step 3: Write the contracts**

```kotlin
// src/main/kotlin/com/qkt/connectivity/Connector.kt
package com.qkt.connectivity

/**
 * A technology qkt trades through — MetaTrader 5 via mt5-gateway, the Bybit v5 API, Rithmic.
 *
 * One implementation per connector type, discovered by [ConnectorRegistry]. A connector knows how
 * to open trading accounts of its type; everything specific to it stays in its own package.
 */
interface Connector {
    /** What this connector is and what it can trade. */
    val spec: ConnectorSpec

    /**
     * Opens every configured account of this connector's type in one call, so accounts that point
     * at the same gateway or credentials can share one connection. Performs no network I/O —
     * [TradingAccount.verify] is where a connector first talks to the venue. Returns one account
     * per entry in [accounts], in the same order.
     */
    fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount>
}

/** A connector's identity: the `type:` value that selects it and the products it can trade. */
data class ConnectorSpec(
    val type: String,
    val displayName: String,
    val productTypes: Set<ProductType>,
) {
    init {
        require(type.isNotBlank()) { "ConnectorSpec.type must not be blank" }
        require(type == type.lowercase()) { "ConnectorSpec.type must be lowercase: $type" }
    }
}

/** The kinds of product a venue lists. */
enum class ProductType {
    /** A contract for difference quoted by a broker, e.g. XAUUSD on an MT5 account. */
    CFD,

    /** Buying and selling the asset itself, e.g. BTCUSDT spot. */
    SPOT,

    /** A futures contract with no expiry, financed by periodic funding, e.g. a BTCUSDT perpetual. */
    PERPETUAL,

    /** A dated futures contract that expires and settles, e.g. CME MES or a BTC quarterly. */
    FUTURE,
}
```

```kotlin
// src/main/kotlin/com/qkt/connectivity/TradingAccount.kt
package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.common.SymbolCalendars
import com.qkt.marketdata.source.MarketSource

/**
 * One login at one broker, exchange or prop firm, opened through a [Connector].
 *
 * Strategies reach an account through its [symbolPrefix]: a strategy trading `PROP_S01:XAUUSD`
 * trades on the account named `prop_s01`.
 */
interface TradingAccount : AutoCloseable {
    /** The account's `brokers:` entry. */
    val config: AccountConfig

    /** The strategy symbol prefix this account serves, e.g. `PROP_S01:`. */
    val symbolPrefix: String get() = config.symbolPrefix

    /**
     * Connects and checks the venue reports the account the config expects — login, server,
     * live or demo, currency, leverage, position mode, as far as the connector can see them.
     * Throws when the venue is unreachable or anything mismatches; trading must not start then.
     */
    fun verify(): AccountProfile

    /** Creates each strategy's order-entry session on this account. */
    val orderEntry: BrokerFactory

    /** Prices from this account's own feed, or null when the connector supplies none. */
    val marketData: MarketSource?

    /** When each of this account's symbols trades. */
    val tradingHours: SymbolCalendars

    /** Releases connections and files this account holds. Idempotent. */
    override fun close()
}
```

```kotlin
// src/main/kotlin/com/qkt/connectivity/AccountConfig.kt
package com.qkt.connectivity

/**
 * One account's entry under `brokers:` in `qkt.config.yaml`.
 *
 * [settings] holds the entry's scalar fields exactly as written (values not yet resolved — use
 * [ConnectorContext.secrets] for credentials). The nested blocks every connector may use are
 * parsed once by the config loader: [tradingHours] (`calendars`), [symbolAliases] (`aliases`),
 * [disabledOrderTypes] (`capability_restrictions`) and [instrumentOverrides].
 */
data class AccountConfig(
    val name: String,
    val type: String,
    val settings: Map<String, String>,
    val tradingHours: List<Pair<String, String>> = emptyList(),
    val symbolAliases: Map<String, String> = emptyMap(),
    val disabledOrderTypes: List<String> = emptyList(),
    val instrumentOverrides: Map<String, Map<String, String>> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "account name must not be blank" }
    }

    /** The strategy symbol prefix this account serves: the upper-cased name plus `:`. */
    val symbolPrefix: String get() = "${name.uppercase()}:"

    /** The raw scalar value of [field], or null when absent. */
    fun setting(field: String): String? = settings[field]
}
```

```kotlin
// src/main/kotlin/com/qkt/connectivity/AccountProfile.kt
package com.qkt.connectivity

/**
 * What the venue reported about an account when [TradingAccount.verify] connected.
 *
 * [accountId] is a string because venues disagree: MT5 logins are numbers, Rithmic and exchange
 * account ids are not. [description] is the connector's one-line operator summary, printed at
 * startup and in the daemon-started notification.
 */
data class AccountProfile(
    val accountName: String,
    val accountId: String,
    val server: String,
    val type: AccountType,
    val currency: String?,
    val leverage: Int?,
    val description: String,
)

/** Whether an account trades real money. */
enum class AccountType {
    /** Real money. */
    LIVE,

    /** Practice money. */
    DEMO,

    /** A trading competition account. */
    CONTEST,

    /** The venue did not say. Treated as live wherever money is at stake. */
    UNKNOWN,
}
```

```kotlin
// src/main/kotlin/com/qkt/connectivity/ConnectorContext.kt
package com.qkt.connectivity

import com.qkt.common.Clock
import java.nio.file.Path

/**
 * What qkt hands a connector when it opens accounts. Connectors take environment, secrets,
 * time and storage from here rather than reaching for globals.
 *
 * [stateRoot] is null for one-shot commands that keep no state. [strategiesTrading] answers
 * "which deployed strategies trade the account named X" — MT5 uses it to attribute positions it
 * finds at startup; it is empty outside the daemon.
 */
class ConnectorContext(
    val stateRoot: Path?,
    val env: Map<String, String>,
    val clock: Clock,
    val secrets: SecretResolver = SecretResolver(env),
    val strategiesTrading: (accountName: String) -> List<String> = { emptyList() },
)
```

```kotlin
// src/main/kotlin/com/qkt/connectivity/Secret.kt
package com.qkt.connectivity

/** A credential. Prints as `Secret(***)` so it cannot leak into logs, state or notifications. */
@JvmInline
value class Secret(
    private val value: String,
) {
    /** The credential itself — call only at the point it is sent to the venue. */
    fun reveal(): String = value

    override fun toString(): String = "Secret(***)"
}
```

```kotlin
// src/main/kotlin/com/qkt/connectivity/SecretResolver.kt
package com.qkt.connectivity

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves an account's credential fields.
 *
 * Highest precedence first:
 * 1. environment `QKT_BROKER_<NAME>_<FIELD>` (the override MT5 profiles already honour);
 * 2. the config value — `env:VAR`, `${VAR}`, `file:/path` (trailing newline trimmed), or a literal.
 *
 * A reference to a variable or file that does not exist is an error naming the account and field,
 * never a silent empty credential.
 */
class SecretResolver(
    private val env: Map<String, String>,
    private val readFile: (Path) -> String = { Files.readString(it) },
) {
    /** The resolved value of [field] on [account], or null when the field is not configured. */
    fun resolve(
        account: AccountConfig,
        field: String,
    ): Secret? {
        val overrideKey = "QKT_BROKER_${account.name.uppercase().replace('-', '_')}_${field.uppercase()}"
        env[overrideKey]?.let { return Secret(it) }
        val raw = account.setting(field) ?: return null
        val where = "${account.name}.$field"
        val value =
            when {
                raw.startsWith("env:") -> envValue(raw.removePrefix("env:"), where)
                raw.startsWith("\${") && raw.endsWith("}") -> envValue(raw.substring(2, raw.length - 1), where)
                raw.startsWith("file:") -> readFile(Path.of(raw.removePrefix("file:"))).trimEnd('\n', '\r')
                else -> raw
            }
        return Secret(value)
    }

    private fun envValue(
        name: String,
        where: String,
    ): String = env[name] ?: error("$where references environment variable $name, which is not set")
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'com.qkt.connectivity.*' -Pkotlin.compiler.execution.strategy=daemon`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/connectivity src/test/kotlin/com/qkt/connectivity
git commit -m "feat(broker): add trading account and connector contracts"
```

---

### Task 3: Connector registry and account directory

**Files:**
- Create: `src/main/kotlin/com/qkt/connectivity/ConnectorRegistry.kt`, `AccountDirectory.kt`
- Create: `src/main/resources/META-INF/services/com.qkt.connectivity.Connector` (empty until Tasks 5–6)
- Test: `src/test/kotlin/com/qkt/connectivity/AccountDirectoryTest.kt`, `FakeConnector.kt`

**Interfaces:**
- Consumes: Task 2 contracts; `com.qkt.marketdata.source.SymbolPattern`; `com.qkt.common.TradingCalendar`.
- Produces:
  - `class ConnectorRegistry(connectors: List<Connector>)` with `fun find(type: String): Connector?`, `val types: List<String>`, `companion fun discover(loader: ClassLoader = …): ConnectorRegistry`
  - `class AccountDirectory : AutoCloseable` with `companion fun open(accounts: List<AccountConfig>, registry: ConnectorRegistry, context: ConnectorContext): AccountDirectory`, `val accounts: List<TradingAccount>`, `fun byName(name: String): TradingAccount?`, `fun forSymbol(qktSymbol: String): TradingAccount?`, `fun orderEntry(): Map<String, BrokerFactory>`, `fun marketDataRoutes(): List<Pair<SymbolPattern, MarketSource>>`, `fun tradingHoursFor(qktSymbol: String): TradingCalendar?`, `fun verifyAll(): List<Pair<TradingAccount, AccountProfile>>`, `override fun close()`

- [ ] **Step 1: Write the failing tests**

```kotlin
// src/test/kotlin/com/qkt/connectivity/FakeConnector.kt
package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.broker.LogBroker
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource

/** A connector for directory tests: records what it was asked to open and close. */
class FakeConnector(
    type: String,
    private val hours: TradingCalendar = TradingCalendar.fxDefault(),
) : Connector {
    override val spec = ConnectorSpec(type, "Fake $type", setOf(ProductType.CFD))
    val openCalls = mutableListOf<List<String>>()
    val closed = mutableListOf<String>()

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> {
        openCalls += accounts.map { it.name }
        return accounts.map { cfg ->
            object : TradingAccount {
                override val config = cfg
                override fun verify() =
                    AccountProfile(cfg.name, "id-${cfg.name}", "srv", AccountType.DEMO, "USD", 100, "${cfg.name}: fake")
                override val orderEntry: BrokerFactory = { _, _, _, _, _ -> LogBroker() }
                override val marketData: MarketSource = NullMarketSource
                override val tradingHours = SymbolCalendars(emptyList(), hours)
                override fun close() {
                    closed += cfg.name
                }
            }
        }
    }
}
```

```kotlin
// src/test/kotlin/com/qkt/connectivity/AccountDirectoryTest.kt
package com.qkt.connectivity

import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccountDirectoryTest {
    private val context = ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock())

    private fun acct(
        name: String,
        type: String?,
    ) = AccountConfig(name, type ?: "", if (type == null) emptyMap() else mapOf("type" to type))

    @Test
    fun `each connector opens all of its accounts in one call, in config order`() {
        val mt5 = FakeConnector("mt5")
        val bybit = FakeConnector("bybit", TradingCalendar.crypto())
        val dir =
            AccountDirectory.open(
                listOf(acct("a", "mt5"), acct("bybit_spot", "bybit"), acct("b", "mt5")),
                ConnectorRegistry(listOf(mt5, bybit)),
                context,
            )
        assertThat(mt5.openCalls).containsExactly(listOf("a", "b"))
        assertThat(bybit.openCalls).containsExactly(listOf("bybit_spot"))
        assertThat(dir.accounts.map { it.config.name }).containsExactly("a", "bybit_spot", "b")
    }

    @Test
    fun `an unknown or missing type is refused, naming the known types`() {
        val registry = ConnectorRegistry(listOf(FakeConnector("mt5")))
        assertThatThrownBy { AccountDirectory.open(listOf(acct("x", "rithmic")), registry, context) }
            .hasMessageContaining("x")
            .hasMessageContaining("rithmic")
            .hasMessageContaining("mt5")
        assertThatThrownBy { AccountDirectory.open(listOf(acct("y", null)), registry, context) }
            .hasMessageContaining("y")
            .hasMessageContaining("type")
    }

    @Test
    fun `lookups go by account name and by strategy symbol prefix`() {
        val dir =
            AccountDirectory.open(
                listOf(acct("prop_s01", "mt5"), acct("bybit_spot", "bybit")),
                ConnectorRegistry(listOf(FakeConnector("mt5"), FakeConnector("bybit", TradingCalendar.crypto()))),
                context,
            )
        assertThat(dir.byName("PROP_S01")?.config?.name).isEqualTo("prop_s01")
        assertThat(dir.forSymbol("PROP_S01:XAUUSD")?.config?.name).isEqualTo("prop_s01")
        assertThat(dir.forSymbol("OTHER:XAUUSD")).isNull()
        assertThat(dir.orderEntry().keys).containsExactly("prop_s01", "bybit_spot")
        assertThat(dir.tradingHoursFor("BYBIT_SPOT:BTCUSDT")?.name).isEqualTo("crypto")
        assertThat(dir.tradingHoursFor("NOPE:BTCUSDT")).isNull()
        assertThat(dir.marketDataRoutes().map { it.first.matches("PROP_S01:XAUUSD") }).containsExactly(true, false)
    }

    @Test
    fun `verifyAll returns every profile and close closes every account`() {
        val mt5 = FakeConnector("mt5")
        val dir = AccountDirectory.open(listOf(acct("a", "mt5"), acct("b", "mt5")), ConnectorRegistry(listOf(mt5)), context)
        assertThat(dir.verifyAll().map { it.second.description }).containsExactly("a: fake", "b: fake")
        dir.close()
        assertThat(mt5.closed).containsExactly("a", "b")
    }

    @Test
    fun `two connectors claiming one type is a startup error`() {
        assertThatThrownBy { ConnectorRegistry(listOf(FakeConnector("mt5"), FakeConnector("mt5"))) }
            .hasMessageContaining("mt5")
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'com.qkt.connectivity.AccountDirectoryTest' -Pkotlin.compiler.execution.strategy=daemon`
Expected: compilation FAIL, `Unresolved reference: AccountDirectory`.

- [ ] **Step 3: Implement**

```kotlin
// src/main/kotlin/com/qkt/connectivity/ConnectorRegistry.kt
package com.qkt.connectivity

import java.util.ServiceLoader

/**
 * Every [Connector] qkt can use, keyed by [ConnectorSpec.type].
 *
 * [discover] finds connectors through `META-INF/services/com.qkt.connectivity.Connector` — the
 * built-in ones today, plugin JARs later — so adding a connector never edits this class.
 */
class ConnectorRegistry(
    connectors: List<Connector>,
) {
    private val byType: Map<String, Connector> =
        connectors
            .groupBy { it.spec.type }
            .mapValues { (type, claimants) ->
                require(claimants.size == 1) { "more than one connector claims type '$type'" }
                claimants.single()
            }

    /** The connector for [type], or null when none is installed. */
    fun find(type: String): Connector? = byType[type]

    /** Installed connector types, sorted. */
    val types: List<String> get() = byType.keys.sorted()

    companion object {
        /** Every connector registered as a service on [loader]. */
        fun discover(loader: ClassLoader = Connector::class.java.classLoader): ConnectorRegistry =
            ConnectorRegistry(ServiceLoader.load(Connector::class.java, loader).toList())
    }
}
```

```kotlin
// src/main/kotlin/com/qkt/connectivity/AccountDirectory.kt
package com.qkt.connectivity

import com.qkt.broker.BrokerFactory
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.SymbolPattern

/**
 * Every trading account configured on this process, opened through its connector.
 *
 * The one lookup the rest of qkt uses: by account name, or by the prefix a strategy symbol carries
 * (`PROP_S01:XAUUSD` → account `prop_s01`). Accounts keep config order.
 */
class AccountDirectory private constructor(
    val accounts: List<TradingAccount>,
) : AutoCloseable {
    private val byLowerName: Map<String, TradingAccount> = accounts.associateBy { it.config.name.lowercase() }

    /** The account named [name], ignoring case. */
    fun byName(name: String): TradingAccount? = byLowerName[name.lowercase()]

    /** The account serving [qktSymbol]'s prefix, or null for an unprefixed or unknown one. */
    fun forSymbol(qktSymbol: String): TradingAccount? {
        val prefix = qktSymbol.substringBefore(':', missingDelimiterValue = "")
        return if (prefix.isEmpty()) null else byName(prefix)
    }

    /** Each account's order-entry factory, keyed by lower-case account name. */
    fun orderEntry(): Map<String, BrokerFactory> = accounts.associate { it.config.name.lowercase() to it.orderEntry }

    /** One prefix route per account that supplies market data, in config order. */
    fun marketDataRoutes(): List<Pair<SymbolPattern, MarketSource>> =
        accounts.mapNotNull { account -> account.marketData?.let { SymbolPattern.prefix(account.symbolPrefix) to it } }

    /** The trading hours governing [qktSymbol] on its account, or null when no account serves it. */
    fun tradingHoursFor(qktSymbol: String): TradingCalendar? =
        forSymbol(qktSymbol)?.tradingHours?.calendarFor(qktSymbol.substringAfter(':'))

    /** Verifies every account in config order; the first failure throws and nothing trades. */
    fun verifyAll(): List<Pair<TradingAccount, AccountProfile>> = accounts.map { it to it.verify() }

    override fun close() {
        accounts.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * Opens [configs] through [registry]. A missing or unknown `type` is refused before any
         * connector runs, naming the installed types.
         */
        fun open(
            configs: List<AccountConfig>,
            registry: ConnectorRegistry,
            context: ConnectorContext,
        ): AccountDirectory {
            for (cfg in configs) {
                require(cfg.type.isNotBlank()) {
                    "brokers.${cfg.name} has no type; set type to one of: ${registry.types.joinToString()}"
                }
                requireNotNull(registry.find(cfg.type)) {
                    "brokers.${cfg.name} has type '${cfg.type}', which no installed connector provides " +
                        "(installed: ${registry.types.joinToString()})"
                }
            }
            val opened = mutableMapOf<String, TradingAccount>()
            try {
                for ((type, group) in configs.groupBy { it.type }) {
                    val connector = registry.find(type) ?: error("unreachable: type '$type' was checked")
                    val accounts = connector.open(group, context)
                    check(accounts.map { it.config.name } == group.map { it.name }) {
                        "connector '$type' must open exactly the accounts it was given, in order"
                    }
                    accounts.forEach { opened[it.config.name] = it }
                }
            } catch (e: Exception) {
                opened.values.forEach { runCatching { it.close() } }
                throw e
            }
            return AccountDirectory(configs.map { opened.getValue(it.name) })
        }
    }
}
```

Create the services file empty: `mkdir -p src/main/resources/META-INF/services && : > src/main/resources/META-INF/services/com.qkt.connectivity.Connector`.

`LogBroker` constructor: check `src/main/kotlin/com/qkt/broker/LogBroker.kt`; if it needs arguments, pass the minimal ones there instead of `LogBroker()`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'com.qkt.connectivity.*' -Pkotlin.compiler.execution.strategy=daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/connectivity src/main/resources/META-INF src/test/kotlin/com/qkt/connectivity
git commit -m "feat(broker): add connector registry and account directory"
```

---

### Task 4: LiveSession asks for abilities, not for MT5

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/InstrumentProvider.kt`, `ServerTimeZoneProvider.kt`, `TicketAttributionProvider.kt`
- Modify: `src/main/kotlin/com/qkt/connector/mt5/MT5Broker.kt` (class header + three overrides)
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` (the three `filterIsInstance<…MT5Broker>()` sites)
- Test: `src/test/kotlin/com/qkt/app/LiveSessionBrokerAbilitiesTest.kt`

**Interfaces:**
- Produces:
  - `interface InstrumentProvider { fun instrumentRegistry(): InstrumentRegistry }`
  - `interface ServerTimeZoneProvider { fun serverTimeZone(): java.time.ZoneId }`
  - `interface TicketAttributionProvider { fun ticketAttributions(): Map<String, String> }`

- [ ] **Step 1: Write the failing test**

Model it on an existing `LiveSession` test that injects `brokerFactories` (see `LiveSessionBrokerCoverageTest`). The test builds a `LiveSession` whose only broker is a small non-MT5 `Broker` implementing all three abilities, and asserts:
- the session's instrument registry resolves the symbol the fake provider knows (`session` exposes it through sizing — assert via `LiveSession.instrumentRegistryForTest()` if present, else through a `SIZING RISK` order's computed quantity);
- a `SCHEDULE … BROKER` strategy resolves in the fake's zone;
- the fake's attributed ticket is recorded in the session's ticket attribution.

Read `LiveSessionBrokerCoverageTest.kt` first and reuse its session builder; keep the new test in the same style.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests 'com.qkt.app.LiveSessionBrokerAbilitiesTest' -Pkotlin.compiler.execution.strategy=daemon`
Expected: FAIL — registry/zone/attribution come only from `MT5Broker`.

- [ ] **Step 3: Implement**

```kotlin
// src/main/kotlin/com/qkt/broker/InstrumentProvider.kt
package com.qkt.broker

import com.qkt.instrument.InstrumentRegistry

/**
 * Opt-in ability of an order-entry session: the venue's contract specs for the symbols it trades.
 * The live session layers every provider's registry so sizing and P&L use the venue's own specs.
 */
interface InstrumentProvider {
    fun instrumentRegistry(): InstrumentRegistry
}
```

```kotlin
// src/main/kotlin/com/qkt/broker/ServerTimeZoneProvider.kt
package com.qkt.broker

import java.time.ZoneId

/** Opt-in ability: the broker server's clock zone, used by `SCHEDULE … BROKER`. */
interface ServerTimeZoneProvider {
    fun serverTimeZone(): ZoneId
}
```

```kotlin
// src/main/kotlin/com/qkt/broker/TicketAttributionProvider.kt
package com.qkt.broker

/**
 * Opt-in ability: venue tickets found at startup and the strategy each belongs to, keyed by
 * ticket. The live session mirrors them so its state poller can name their strategy.
 */
interface TicketAttributionProvider {
    fun ticketAttributions(): Map<String, String>
}
```

In `MT5Broker.kt` add the three interfaces to the class's supertype list and:

```kotlin
override fun instrumentRegistry(): com.qkt.instrument.InstrumentRegistry = MT5InstrumentRegistry(this)

override fun serverTimeZone(): java.time.ZoneId = profile.serverTimeZone.asZoneId()
```

Mark the existing `fun ticketAttributions()` as `override`.

In `LiveSession.kt` replace, exactly:

```kotlin
builtBrokers.filterIsInstance<com.qkt.connector.mt5.MT5Broker>().map { com.qkt.connector.mt5.MT5InstrumentRegistry(it) }
// becomes
builtBrokers.filterIsInstance<com.qkt.broker.InstrumentProvider>().map { it.instrumentRegistry() }

for (b in builtBrokers.filterIsInstance<com.qkt.connector.mt5.MT5Broker>()) {
// becomes
for (b in builtBrokers.filterIsInstance<com.qkt.broker.TicketAttributionProvider>()) {

val mt5 = builtBrokers.filterIsInstance<com.qkt.connector.mt5.MT5Broker>().firstOrNull()
if (mt5 != null) { val zone: java.time.ZoneId = mt5.profile.serverTimeZone.asZoneId() …
// becomes
val clock = builtBrokers.filterIsInstance<com.qkt.broker.ServerTimeZoneProvider>().firstOrNull()
if (clock != null) { val zone: java.time.ZoneId = clock.serverTimeZone() …
```

Update the surrounding KDoc/comments to say "brokers that provide instruments / a server clock / recovered attributions" instead of MT5.

- [ ] **Step 4: Run the new test and the existing LiveSession suites**

Run: `./gradlew test --tests 'com.qkt.app.LiveSession*' --tests 'com.qkt.parity.*' -Pkotlin.compiler.execution.strategy=daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker src/main/kotlin/com/qkt/connector/mt5/MT5Broker.kt src/main/kotlin/com/qkt/app/LiveSession.kt src/test/kotlin/com/qkt/app/LiveSessionBrokerAbilitiesTest.kt
git commit -m "refactor(app): ask brokers for abilities instead of mt5"
```

---

### Task 5: MT5 connector

**Files:**
- Create: `src/main/kotlin/com/qkt/connector/mt5/Mt5Connector.kt` (connector + `Mt5TradingAccount` + `Mt5MarketDataIdentity` moved from `cli/MarketSourceFactory.kt`)
- Modify: `src/main/resources/META-INF/services/com.qkt.connectivity.Connector` (add `com.qkt.connector.mt5.Mt5Connector`)
- Test: `src/test/kotlin/com/qkt/connector/mt5/Mt5ConnectorTest.kt`

**Interfaces:**
- Consumes: Task 2–3 contracts; `MT5BrokerProfileLoader.load(raw, defaults, env, calendars, aliases, capabilityRestrictions, instrumentOverrides)`; `MT5Client(gatewayUrl, serverTimeZone, httpTimeoutMs, retryAttempts, apiKey, readCache, transportJournal)`; `MT5ReadCache(ttlMs)`; `MT5TransportJournal(dir, name, clock)`; `MT5AccountVerifier.fetchAndVerify(profile)` / `.describe(profile, account)`; `MT5Broker(profile, bus, clock, priceTracker, client, strategyName, siblingsLookup)`; `Mt5MarketSource(profile)`, `SharedLiveMarketSource`, `CachedHistoricalMarketSource`, `PrefixRemapMarketSource(delegate, delegatePrefix, localPrefix)`.
- Produces: `class Mt5Connector(accountFetcher: (MT5BrokerProfile) -> MT5AccountInfo = { MT5AccountVerifier.fetchAndVerify(it) }) : Connector`, type `"mt5"`; `class Mt5TradingAccount : TradingAccount` with `val profile: MT5BrokerProfile`; `internal data class Mt5MarketDataIdentity`; `internal fun groupByMarketDataIdentity(profiles): List<List<MT5BrokerProfile>>`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// src/test/kotlin/com/qkt/connector/mt5/Mt5ConnectorTest.kt
package com.qkt.connector.mt5

import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.ConnectorContext
import com.qkt.marketdata.source.PrefixRemapMarketSource
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5ConnectorTest {
    private val context = ConnectorContext(stateRoot = null, env = emptyMap(), clock = SystemClock())

    private fun acct(
        name: String,
        magic: Int,
        gateway: String = "http://gw:5001",
    ) = AccountConfig(
        name = name,
        type = "mt5",
        settings = mapOf("type" to "mt5", "extends" to "exness", "gateway_url" to gateway, "magic" to "$magic"),
    )

    @Test
    fun `profiles match the legacy loader field for field`() {
        val accounts = listOf(acct("exness_s0", 1001), acct("exness_s1", 1002))
        val opened = Mt5Connector().open(accounts, context).map { (it as Mt5TradingAccount).profile }
        val legacy =
            MT5BrokerProfileLoader().load(
                raw = accounts.associate { it.name to it.settings },
                defaults = MT5DefaultProfiles.all,
                env = emptyMap(),
            )
        assertThat(opened).containsExactlyInAnyOrderElementsOf(legacy)
    }

    @Test
    fun `accounts on one feed share a market data source, others get their own`() {
        val opened =
            Mt5Connector().open(
                listOf(acct("a", 1), acct("b", 2), acct("c", 3, gateway = "http://other:5001")),
                context,
            )
        val (a, b, c) = opened.map { it.marketData }
        assertThat(b).isInstanceOf(PrefixRemapMarketSource::class.java)
        assertThat(a).isNotInstanceOf(PrefixRemapMarketSource::class.java)
        assertThat(c).isNotInstanceOf(PrefixRemapMarketSource::class.java).isNotSameAs(a)
        assertThat(b?.supports("B:XAUUSD")).isTrue()
    }

    @Test
    fun `verify maps the venue account and keeps the operator line byte-identical`() {
        val info =
            MT5AccountInfo(
                balance = BigDecimal("50000"),
                equity = BigDecimal("48497.80"),
                currency = "USD",
                leverage = 100,
                marginMode = MARGIN_MODE_HEDGING,
                login = 26645824L,
                server = "FivePercentOnline-Real",
                tradeMode = MT5TradeMode.REAL.wireValue,
            )
        val account = Mt5Connector(accountFetcher = { info }).open(listOf(acct("prop_s01", 7)), context).single()
        val profile = account.verify()
        assertThat(profile.accountId).isEqualTo("26645824")
        assertThat(profile.server).isEqualTo("FivePercentOnline-Real")
        assertThat(profile.type).isEqualTo(AccountType.LIVE)
        assertThat(profile.description).isEqualTo(MT5AccountVerifier.describe((account as Mt5TradingAccount).profile, info))
    }

    @Test
    fun `trading hours come from the account's calendars block`() {
        val cfg = acct("a", 1).copy(tradingHours = listOf("BTC*" to "crypto", "*" to "fx"))
        val account = Mt5Connector().open(listOf(cfg), context).single()
        assertThat(account.tradingHours.calendarFor("BTCUSD").name).isEqualTo("crypto")
        assertThat(account.tradingHours.calendarFor("EURUSD").name).isEqualTo("fx")
    }
}
```

Also move the two grouping tests from `src/test/kotlin/com/qkt/cli/MarketSourceFactoryTest.kt` (`profiles identical except name and magic share one market-data group`, `profiles differing in any market-data field keep their own groups`) into `Mt5ConnectorTest`, calling `groupByMarketDataIdentity(...)` in the new package, assertions unchanged.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'com.qkt.connector.mt5.Mt5ConnectorTest' -Pkotlin.compiler.execution.strategy=daemon`
Expected: compilation FAIL, `Unresolved reference: Mt5Connector`.

- [ ] **Step 3: Implement**

```kotlin
// src/main/kotlin/com/qkt/connector/mt5/Mt5Connector.kt
package com.qkt.connector.mt5

import com.qkt.broker.BrokerFactory
import com.qkt.common.SymbolCalendars
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.Connector
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorSpec
import com.qkt.connectivity.ProductType
import com.qkt.connectivity.TradingAccount
import com.qkt.connector.mt5.marketdata.Mt5MarketSource
import com.qkt.marketdata.source.CachedHistoricalMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.PrefixRemapMarketSource
import com.qkt.marketdata.source.SharedLiveMarketSource

/**
 * MetaTrader 5 through mt5-gateway: CFD accounts at MT5 brokers and prop firms.
 *
 * Opens every `type: mt5` account together so connections are shared exactly as the daemon always
 * has: accounts on one gateway and API key share a read cache; each account has one [MT5Client]
 * shared by its strategies; accounts with the same [Mt5MarketDataIdentity] share one live feed,
 * the first owning the poller and the rest remapped onto it.
 */
class Mt5Connector(
    private val accountFetcher: (MT5BrokerProfile) -> MT5AccountInfo = { MT5AccountVerifier.fetchAndVerify(it) },
) : Connector {
    override val spec = ConnectorSpec(type = "mt5", displayName = "MetaTrader 5", productTypes = setOf(ProductType.CFD))

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> {
        val profiles =
            MT5BrokerProfileLoader().load(
                raw = accounts.associate { it.name to it.settings },
                defaults = MT5DefaultProfiles.all,
                env = context.env,
                calendars = accounts.associate { it.name to it.tradingHours },
                aliases = accounts.associate { it.name to it.symbolAliases },
                capabilityRestrictions = accounts.associate { it.name to it.disabledOrderTypes },
                instrumentOverrides = accounts.associate { it.name to it.instrumentOverrides },
            )
        val readCaches = profiles.map { it.gatewayUrl to it.apiKey }.distinct().associateWith { MT5ReadCache(SHARED_READ_TTL_MS) }
        val feeds = marketDataFeeds(profiles)
        val byName = profiles.associateBy { it.name }
        return accounts.map { cfg ->
            val profile = byName[cfg.name] ?: error("MT5 profile for '${cfg.name}' did not load")
            val journal =
                context.stateRoot?.let {
                    MT5TransportJournal(it.resolve("mt5-transport-journal"), profile.name, context.clock)
                }
            val client =
                MT5Client(
                    gatewayUrl = profile.gatewayUrl,
                    serverTimeZone = profile.serverTimeZone,
                    httpTimeoutMs = profile.httpTimeoutMs,
                    retryAttempts = profile.retryAttempts,
                    apiKey = profile.apiKey,
                    readCache = readCaches.getValue(profile.gatewayUrl to profile.apiKey),
                    transportJournal = journal,
                )
            Mt5TradingAccount(cfg, profile, client, feeds.getValue(profile.name), journal, accountFetcher, context.strategiesTrading)
        }
    }

    /** One lazily built feed per market-data identity; later accounts in a group remap onto the first. */
    private fun marketDataFeeds(profiles: List<MT5BrokerProfile>): Map<String, Lazy<MarketSource>> {
        val feeds = mutableMapOf<String, Lazy<MarketSource>>()
        for (group in groupByMarketDataIdentity(profiles)) {
            val canonical = group.first()
            val canonicalPrefix = "${canonical.name.uppercase()}:"
            val shared = lazy { CachedHistoricalMarketSource(SharedLiveMarketSource(Mt5MarketSource(canonical))) as MarketSource }
            feeds[canonical.name] = shared
            for (profile in group.drop(1)) {
                feeds[profile.name] =
                    lazy {
                        PrefixRemapMarketSource(
                            delegate = shared.value,
                            delegatePrefix = canonicalPrefix,
                            localPrefix = "${profile.name.uppercase()}:",
                        )
                    }
            }
        }
        return feeds
    }

    private companion object {
        /** Below the 1s venue poll cadence: collapses sibling reads without hiding a poll round. */
        const val SHARED_READ_TTL_MS: Long = 500L
    }
}

/** One MT5 login, opened by [Mt5Connector]. */
class Mt5TradingAccount internal constructor(
    override val config: AccountConfig,
    /** The resolved MT5 profile — for MT5-only operator tools; core code must not read it. */
    val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val feed: Lazy<MarketSource>,
    private val journal: MT5TransportJournal?,
    private val accountFetcher: (MT5BrokerProfile) -> MT5AccountInfo,
    private val strategiesTrading: (String) -> List<String>,
) : TradingAccount {
    override val tradingHours: SymbolCalendars get() = profile.symbolCalendars

    override val marketData: MarketSource get() = feed.value

    override val orderEntry: BrokerFactory = { bus, clock, priceTracker, _, strategyName ->
        MT5Broker(
            profile = profile,
            bus = bus,
            clock = clock,
            priceTracker = priceTracker,
            client = client,
            strategyName = strategyName,
            siblingsLookup = {
                if (strategyName == null) emptyList() else strategiesTrading(profile.name).filter { it != strategyName }
            },
        )
    }

    override fun verify(): AccountProfile {
        val info = accountFetcher(profile)
        return AccountProfile(
            accountName = profile.name,
            accountId = info.login.toString(),
            server = info.server,
            type =
                when (MT5TradeMode.fromWire(info.tradeMode)) {
                    MT5TradeMode.REAL -> AccountType.LIVE
                    MT5TradeMode.DEMO -> AccountType.DEMO
                    MT5TradeMode.CONTEST -> AccountType.CONTEST
                    null -> AccountType.UNKNOWN
                },
            currency = info.currency,
            leverage = info.leverage,
            description = MT5AccountVerifier.describe(profile, info),
        )
    }

    override fun close() {
        journal?.let { runCatching { it.close() } }
    }
}
```

Move `Mt5MarketDataIdentity` and `groupByMarketDataIdentity` from `cli/MarketSourceFactory.kt` into a new `src/main/kotlin/com/qkt/connector/mt5/Mt5MarketDataIdentity.kt` (package `com.qkt.connector.mt5`, `internal`, unchanged bodies; `groupByMarketDataIdentity` becomes a top-level `internal fun`). If `MT5AccountVerifier.fetchAndVerify` / `MT5TradeMode` / `MT5AccountInfo` fields differ from the names used above, use the real names — they are all in `connector/mt5`.

Append `com.qkt.connector.mt5.Mt5Connector` to `src/main/resources/META-INF/services/com.qkt.connectivity.Connector`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'com.qkt.connector.mt5.*' -Pkotlin.compiler.execution.strategy=daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/connector/mt5 src/main/kotlin/com/qkt/cli/MarketSourceFactory.kt src/main/resources/META-INF src/test/kotlin/com/qkt/connector/mt5 src/test/kotlin/com/qkt/cli/MarketSourceFactoryTest.kt
git commit -m "feat(broker): open mt5 accounts through the connector contract"
```

---

### Task 6: Bybit connector, configured like any other account

**Files:**
- Create: `src/main/kotlin/com/qkt/connector/bybit/BybitConnector.kt`
- Modify: `src/main/kotlin/com/qkt/connector/bybit/BybitClient.kt` (constructor: no environment reads)
- Modify: `src/test/kotlin/com/qkt/connector/bybit/BybitTestnetExerciseTest.kt`, `spot/BybitSpotLiveSmokeTest.kt` (pass env values explicitly)
- Modify: services file (add `com.qkt.connector.bybit.BybitConnector`)
- Test: `src/test/kotlin/com/qkt/connector/bybit/BybitConnectorTest.kt`

**Interfaces:**
- Produces: `class BybitConnector(clientFactory: (BybitCredentials) -> BybitClient = …) : Connector`, type `"bybit"`; `data class BybitCredentials(apiKey: Secret, apiSecret: Secret, testnet: Boolean, recvWindowMs: Long, accountType: String)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// src/test/kotlin/com/qkt/connector/bybit/BybitConnectorTest.kt
package com.qkt.connector.bybit

import com.qkt.common.SystemClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitConnectorTest {
    private val env = mapOf("BYBIT_API_KEY" to "k", "BYBIT_API_SECRET" to "s")
    private val context = ConnectorContext(stateRoot = null, env = env, clock = SystemClock())

    private fun acct(
        name: String,
        category: String,
        vararg extra: Pair<String, String>,
    ) = AccountConfig(
        name,
        "bybit",
        mapOf("type" to "bybit", "category" to category, "api_key" to "env:BYBIT_API_KEY", "api_secret" to "env:BYBIT_API_SECRET") + extra,
    )

    @Test
    fun `spot and linear accounts serve their existing prefixes with crypto hours`() {
        val (spot, linear) = BybitConnector().open(listOf(acct("bybit_spot", "spot"), acct("bybit_linear", "linear")), context)
        assertThat(spot.marketData?.supports("BYBIT_SPOT:BTCUSDT")).isTrue()
        assertThat(linear.marketData?.supports("BYBIT_LINEAR:BTCUSDT")).isTrue()
        assertThat(spot.tradingHours.calendarFor("BTCUSDT").name).isEqualTo("crypto")
    }

    @Test
    fun `accounts with the same credentials share one client, built only when trading needs it`() {
        val built = mutableListOf<BybitCredentials>()
        val connector = BybitConnector(clientFactory = { creds -> built += creds; BybitClient(creds.apiKey.reveal(), creds.apiSecret.reveal(), creds.testnet, creds.recvWindowMs, creds.accountType) })
        val accounts = connector.open(listOf(acct("bybit_spot", "spot"), acct("bybit_linear", "linear")), context)
        assertThat(built).isEmpty()
        accounts.forEach { it.orderEntry }
        (accounts[0] as BybitTradingAccount).client()
        (accounts[1] as BybitTradingAccount).client()
        assertThat(built).hasSize(1)
        assertThat(built.single().testnet).isTrue()
        accounts.forEach { it.close() }
    }

    @Test
    fun `an account name that does not match its category is refused`() {
        assertThatThrownBy { BybitConnector().open(listOf(acct("my_bybit", "linear")), context) }
            .hasMessageContaining("my_bybit")
            .hasMessageContaining("bybit_linear")
    }

    @Test
    fun `missing credentials are refused naming the field`() {
        val noKey = AccountConfig("bybit_spot", "bybit", mapOf("category" to "spot"))
        assertThatThrownBy { BybitConnector().open(listOf(noKey), context) }
            .hasMessageContaining("bybit_spot.api_key")
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'com.qkt.connector.bybit.BybitConnectorTest' -Pkotlin.compiler.execution.strategy=daemon`
Expected: compilation FAIL, `Unresolved reference: BybitConnector`.

- [ ] **Step 3: Implement**

In `BybitClient.kt` change the constructor to take explicit values and delete the `System.getenv` fallbacks:

```kotlin
class BybitClient(
    apiKey: String,
    apiSecret: String,
    testnet: Boolean = true,
    recvWindowMs: Long = 5_000L,
    accountType: String = "UNIFIED",
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: Clock = SystemClock(),
    private val wsFactory: (Request, WebSocketListener) -> WebSocket =
        { req, listener -> httpClient.newWebSocket(req, listener) },
) : BybitTransport {
    private val resolvedApiKey: String = apiKey
    private val resolvedApiSecret: String = apiSecret
    private val resolvedTestnet: Boolean = testnet
    private val resolvedRecvWindowMs: Long = recvWindowMs
    private val resolvedAccountType: String = accountType
```

Update the KDoc ("Reads API credentials from env vars…" → "Credentials and endpoints are passed in; the connector resolves them from config."). In the two opt-in live tests replace `BybitClient(testnet = true)` with
`BybitClient(apiKey = System.getenv("BYBIT_API_KEY") ?: "", apiSecret = System.getenv("BYBIT_API_SECRET") ?: "", testnet = true)` (they are skipped without keys, unchanged). Fix any other test call site the compiler reports by passing the values it previously got from defaults.

```kotlin
// src/main/kotlin/com/qkt/connector/bybit/BybitConnector.kt
package com.qkt.connector.bybit

import com.qkt.broker.BrokerFactory
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountProfile
import com.qkt.connectivity.AccountType
import com.qkt.connectivity.Connector
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorSpec
import com.qkt.connectivity.ProductType
import com.qkt.connectivity.Secret
import com.qkt.connectivity.TradingAccount
import com.qkt.connector.bybit.linear.BybitLinearBroker
import com.qkt.connector.bybit.marketdata.BybitLinearMarketSource
import com.qkt.connector.bybit.marketdata.BybitSpotMarketSource
import com.qkt.connector.bybit.spot.BybitSpotBroker
import com.qkt.marketdata.source.MarketSource

/** Resolved credentials and endpoint for one Bybit login. Accounts with equal credentials share a client. */
data class BybitCredentials(
    val apiKey: Secret,
    val apiSecret: Secret,
    val testnet: Boolean,
    val recvWindowMs: Long,
    val accountType: String,
)

/**
 * Bybit v5 — spot and USDT perpetual (linear) accounts.
 *
 * Each `type: bybit` entry is one product category on a Bybit login:
 *
 * ```yaml
 * bybit_linear:
 *   type: bybit
 *   category: linear            # spot | linear
 *   api_key: env:BYBIT_API_KEY
 *   api_secret: env:BYBIT_API_SECRET
 *   testnet: "true"
 * ```
 *
 * Until the Bybit brokers learn configurable prefixes, the entry must be named after its
 * category (`bybit_spot`, `bybit_linear`) — the prefixes strategies already use.
 */
class BybitConnector(
    private val clientFactory: (BybitCredentials) -> BybitClient = { c ->
        BybitClient(c.apiKey.reveal(), c.apiSecret.reveal(), c.testnet, c.recvWindowMs, c.accountType)
    },
) : Connector {
    override val spec =
        ConnectorSpec(type = "bybit", displayName = "Bybit", productTypes = setOf(ProductType.SPOT, ProductType.PERPETUAL))

    override fun open(
        accounts: List<AccountConfig>,
        context: ConnectorContext,
    ): List<TradingAccount> {
        val shared = mutableMapOf<BybitCredentials, SharedClient>()
        return accounts.map { cfg ->
            val category = cfg.setting("category") ?: error("brokers.${cfg.name}.category is required: spot or linear")
            require(category == "spot" || category == "linear") {
                "brokers.${cfg.name}.category must be spot or linear, got '$category'"
            }
            val expectedName = "bybit_$category"
            require(cfg.name.equals(expectedName, ignoreCase = true)) {
                "Bybit $category account must be named '$expectedName' (it serves the ${expectedName.uppercase()}: prefix); got '${cfg.name}'"
            }
            val creds =
                BybitCredentials(
                    apiKey = context.secrets.resolve(cfg, "api_key") ?: error("brokers.${cfg.name}.api_key is required"),
                    apiSecret = context.secrets.resolve(cfg, "api_secret") ?: error("brokers.${cfg.name}.api_secret is required"),
                    testnet = cfg.setting("testnet")?.equals("false", ignoreCase = true) != true,
                    recvWindowMs = cfg.setting("recv_window_ms")?.toLong() ?: 5_000L,
                    accountType = cfg.setting("account_type") ?: "UNIFIED",
                )
            BybitTradingAccount(cfg, category, shared.getOrPut(creds) { SharedClient { clientFactory(creds) } })
        }
    }
}

/** One Bybit client, built on first use and closed when the last account using it closes. */
internal class SharedClient(
    private val build: () -> BybitClient,
) {
    private var client: BybitClient? = null
    private var users = 0

    @Synchronized
    fun acquire(): BybitClient = client ?: build().also { client = it }

    @Synchronized
    fun join() {
        users++
    }

    @Synchronized
    fun leave() {
        users--
        if (users <= 0) {
            client?.let { runCatching { it.close() } }
            client = null
        }
    }
}

/** One Bybit product category on one login, opened by [BybitConnector]. */
class BybitTradingAccount internal constructor(
    override val config: AccountConfig,
    private val category: String,
    private val shared: SharedClient,
) : TradingAccount {
    init {
        shared.join()
    }

    private var closed = false

    /** The account's client, connecting nothing until first called. */
    internal fun client(): BybitClient = shared.acquire()

    override val tradingHours: SymbolCalendars = SymbolCalendars(emptyList(), TradingCalendar.crypto())

    override val marketData: MarketSource by lazy {
        if (category == "spot") BybitSpotMarketSource() else BybitLinearMarketSource()
    }

    override val orderEntry: BrokerFactory =
        if (category == "spot") {
            { bus, clock, _, _, _ -> BybitSpotBroker(client(), bus, clock) }
        } else {
            { bus, clock, _, positions, _ -> BybitLinearBroker(client(), bus, clock, positions) }
        }

    override fun verify(): AccountProfile {
        val client = client()
        client.connect()
        val testnet = client.restBaseUrl.contains("testnet")
        return AccountProfile(
            accountName = config.name,
            accountId = config.name,
            server = client.restBaseUrl,
            type = if (testnet) AccountType.DEMO else AccountType.LIVE,
            currency = null,
            leverage = null,
            description = "${config.name}: bybit category=$category ${if (testnet) "testnet" else "mainnet"} account=${client.accountType}",
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        shared.leave()
    }
}
```

Append `com.qkt.connector.bybit.BybitConnector` to the services file.

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'com.qkt.connector.bybit.*' --tests 'com.qkt.chaos.*' -Pkotlin.compiler.execution.strategy=daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/connector/bybit src/main/resources/META-INF src/test/kotlin/com/qkt/connector/bybit
git commit -m "feat(broker): configure bybit accounts through the connector contract"
```

---

### Task 7: Wire the daemon, `qkt run` and market data through the directory

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/Config.kt` (add `fun accountConfigs(): List<AccountConfig>`)
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt` (lines ~165–350, ~509–586, `liveCalendarFor`)
- Modify: `src/main/kotlin/com/qkt/cli/RunCommand.kt` (~115–135)
- Modify: `src/main/kotlin/com/qkt/cli/MarketSourceFactory.kt` (`composite` signature)
- Test: port `DaemonCommandSourceWiringTest`, `DaemonCommandCalendarTest`, `MarketSourceFactoryTest`; add `ConfigAccountConfigsTest`; add `ConnectorRegistryDiscoveryTest`

**Interfaces:**
- Consumes: `AccountDirectory`, `ConnectorRegistry.discover()`, `ConnectorContext`.
- Produces: `MarketSourceFactory.composite(accountRoutes: List<Pair<SymbolPattern, MarketSource>>, source: String = "tv", hub: HubStoreConfig = HubStoreConfig.NONE, fallbackProvider: () -> MarketSource = …)`; `internal fun liveCalendarFor(qktSymbol: String, directory: AccountDirectory): TradingCalendar`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// src/test/kotlin/com/qkt/cli/ConfigAccountConfigsTest.kt
package com.qkt.cli

import com.qkt.connectivity.ConnectorRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigAccountConfigsTest {
    @Test
    fun `every brokers entry becomes an account config with its nested blocks, in order`() {
        val yaml =
            """
            brokers:
              prop_s01:
                type: mt5
                extends: exness
                gateway_url: http://gw:5001
                magic: 7
                calendars:
                  "BTC*": crypto
                  "*": fx
              bybit_linear:
                type: bybit
                category: linear
            """.trimIndent()
        val cfg = Config.parse(yaml)
        val accounts = cfg.accountConfigs()
        assertThat(accounts.map { it.name }).containsExactly("prop_s01", "bybit_linear")
        assertThat(accounts[0].type).isEqualTo("mt5")
        assertThat(accounts[0].tradingHours).containsExactly("BTC*" to "crypto", "*" to "fx")
        assertThat(accounts[1].setting("category")).isEqualTo("linear")
    }

    @Test
    fun `the built-in connectors are discovered as services`() {
        assertThat(ConnectorRegistry.discover().types).containsExactly("bybit", "mt5")
    }
}
```

If `Config` has no `parse(String)`, use whatever the existing `ConfigTest` uses to load YAML text (read `ConfigTest.kt` first) and keep the assertions.

Port the existing wiring tests, keeping every assertion:
- `DaemonCommandSourceWiringTest`: build routes with `AccountDirectory.open(listOf(AccountConfig("exness", "mt5", …)), ConnectorRegistry.discover(), context)` and pass `directory.marketDataRoutes()` to `composite`. Replace `composite routes BYBIT_SPOT prefix unconditionally` with `composite routes BYBIT_SPOT only when a bybit_spot account is configured` (two assertions: absent → unsupported, present → supported).
- `MarketSourceFactoryTest`: the `enableBybit` tests become the same config-driven pair; the grouping tests already moved in Task 5; `composite serves every profile prefix of a shared group but no unconfigured prefix` builds its profiles through the directory.
- `DaemonCommandCalendarTest`: call `liveCalendarFor(symbol, directory)`; the `BYBIT_LINEAR:BTCUSDT` → crypto assertion is kept by configuring a `bybit_linear` account; `PAPER:SPX` → nyse and `VENUE_A:*` assertions unchanged.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests 'com.qkt.cli.ConfigAccountConfigsTest' --tests 'com.qkt.cli.DaemonCommand*' --tests 'com.qkt.cli.MarketSourceFactoryTest' -Pkotlin.compiler.execution.strategy=daemon`
Expected: compilation FAIL (`accountConfigs`, new `composite`/`liveCalendarFor` signatures).

- [ ] **Step 3: Implement**

`Config.kt`:

```kotlin
/** Every `brokers:` entry as a connector-neutral [com.qkt.connectivity.AccountConfig], in file order. */
fun accountConfigs(): List<com.qkt.connectivity.AccountConfig> =
    brokers.map { (name, fields) ->
        com.qkt.connectivity.AccountConfig(
            name = name,
            type = fields["type"].orEmpty(),
            settings = fields,
            tradingHours = brokerCalendars[name].orEmpty(),
            symbolAliases = brokerAliases[name].orEmpty(),
            disabledOrderTypes = brokerCapabilityRestrictions[name].orEmpty(),
            instrumentOverrides = brokerInstrumentOverrides[name].orEmpty(),
        )
    }
```

`MarketSourceFactory.composite`: replace the `mt5Profiles` parameter and the `enableBybit` parameter with `accountRoutes: List<Pair<SymbolPattern, MarketSource>>`; delete the MT5 grouping loop, the Bybit block and `defaultEnableBybit()`; add `routes.addAll(accountRoutes)` where the MT5 loop was. Update the KDoc to describe account routes. Remove now-unused imports.

`DaemonCommand.kt`, replacing the MT5 profile load, the account preflight, the factory maps and the Bybit client:

```kotlin
val registryRef = AtomicReference<StrategyRegistry?>(null)   // moved up, unchanged meaning
val connectorContext =
    com.qkt.connectivity.ConnectorContext(
        stateRoot = stateDir.stateRoot,
        env = System.getenv(),
        clock = com.qkt.common.SystemClock(),
        strategiesTrading = { accountName ->
            registryRef.get()?.list().orEmpty()
                .filter { handle -> handle.live.streamBrokers().values.any { it.equals(accountName, ignoreCase = true) } }
                .map { it.name }
        },
    )
val directory =
    try {
        com.qkt.connectivity.AccountDirectory.open(
            cfg.accountConfigs(),
            com.qkt.connectivity.ConnectorRegistry.discover(),
            connectorContext,
        )
    } catch (e: Exception) {
        System.err.println("qkt: broker account load failed: ${e.message}")
        runCatching { insightsSink?.close() }
        return ExitCodes.USER_ERROR
    }
val verifiedAccounts =
    try {
        directory.verifyAll()
    } catch (e: Exception) {
        System.err.println("qkt: broker account preflight failed: ${e.message}")
        runCatching { directory.close() }
        runCatching { insightsSink?.close() }
        return ExitCodes.USER_ERROR
    }
val daemonCalendarFor: (String) -> com.qkt.common.TradingCalendar = { qktSymbol -> liveCalendarFor(qktSymbol, directory) }
```

- The REAL-account warning becomes `verifiedAccounts.any { it.second.type == AccountType.LIVE || it.second.type == AccountType.UNKNOWN }` with the same message text.
- `brokerFactories = directory.orderEntry()`.
- `effectiveSourceFactory = sourceFactory ?: MarketSourceFactory.composite(directory.marketDataRoutes(), source = cfg.source, hub = cfg.hub)`.
- Startup logging: `println("[INFO] broker accounts loaded: ${directory.accounts.joinToString { it.config.name }}")` only when non-empty, then per account `println("[INFO] account: ${profile.description}")`. For MT5-only daemons, keep the existing text: print `mt5 broker profiles loaded:` / `mt5 account:` when every account's connector type is `mt5` — this keeps log parsers and runbooks working.
- `NotificationEvent.DaemonStarted.accounts = verifiedAccounts.map { it.second.description }`.
- Delete `mt5TransportJournals`, `mt5ReadCaches`, `mt5Factories`, `bybitClient`, `bybitFactories`, `mt5Profiles`, `mt5Accounts`, and the `SHARED_MT5_READ_TTL_MS` constant; `cleanup()` calls `runCatching { directory.close() }` where it closed journals and the Bybit client.
- `liveCalendarFor(qktSymbol, directory) = directory.tradingHoursFor(qktSymbol) ?: BacktestContext.defaultCalendars().calendarFor(qktSymbol.substringAfter(':'))`.

`RunCommand.kt`: replace the MT5 profile block with

```kotlin
val directory =
    try {
        com.qkt.connectivity.AccountDirectory.open(
            cfg.accountConfigs(),
            com.qkt.connectivity.ConnectorRegistry.discover(),
            com.qkt.connectivity.ConnectorContext(stateRoot = null, env = System.getenv(), clock = com.qkt.common.SystemClock()),
        )
    } catch (e: Exception) {
        println("[WARN] broker account load failed: ${e.message}")
        null
    }
MarketSourceFactory.composite(directory?.marketDataRoutes().orEmpty(), hub = cfg.hub)
```

(`qkt run` never verified accounts; it still does not.)

- [ ] **Step 4: Run the wiring tests, then the whole suite**

Run: `./gradlew test -Pkotlin.compiler.execution.strategy=daemon`
Expected: BUILD SUCCESSFUL; `MT5DaemonE2ETest` and `PortfolioDeployerE2ETest` pass unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli src/test/kotlin/com/qkt/cli
git commit -m "refactor(app): wire daemon and run through the account directory"
```

---

### Task 8: Enforce the dependency direction

**Files:**
- Test: `src/test/kotlin/com/qkt/connectivity/ConnectivityArchitectureTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
// src/test/kotlin/com/qkt/connectivity/ConnectivityArchitectureTest.kt
package com.qkt.connectivity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Keeps connectors swappable: core never names a connector, connectors never reach above the
 * shared model, and neither the contracts nor one connector depend on another connector.
 */
class ConnectivityArchitectureTest {
    private val root: Path = Path.of("src/main/kotlin/com/qkt")

    /** MT5-only operator tools not yet migrated. This list may only shrink. */
    private val mt5ToolAllowList =
        setOf(
            "trade/BotGateway.kt",
            "trade/BotGatewayResult.kt",
            "cli/bot/BotSessionCommand.kt",
            "cli/FetchCommand.kt",
            "cli/InstrumentsCommand.kt",
            "cli/PreflightCommand.kt",
            "cli/BrokersCommand.kt",
            "cli/AuditTicksCommand.kt",
            "cli/Mt5FeedAudit.kt",
            "tools/parity/ParityBarsXauusd.kt",
            "tools/parity/ParityDukascopyMt5Xauusd.kt",
            "tools/parity/ParityTicksXauusd.kt",
        )

    private val forbiddenForConnectors = listOf("app", "cli", "risk", "observe", "dsl", "backtest", "trade", "research")

    private val sources: Map<String, String> by lazy {
        Files.walk(root).use { paths ->
            paths
                .filter { it.extension == "kt" }
                .toList()
                .associate { root.relativize(it).invariantSeparatorsPathString to codeOnly(it.readText()) }
        }
    }

    private fun codeOnly(text: String): String =
        text
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    private fun connectorOf(path: String): String? = Regex("^connector/([^/]+)/").find(path)?.groupValues?.get(1)

    @Test
    fun `nothing outside a connector references it, except the listed mt5 tools`() {
        val offenders =
            sources.flatMap { (path, code) ->
                Regex("com\\.qkt\\.connector\\.([a-z0-9]+)").findAll(code).map { it.groupValues[1] }.distinct()
                    .filter { it != connectorOf(path) }
                    .filterNot { it == "mt5" && path in mt5ToolAllowList }
                    .map { "$path -> connector.$it" }
                    .toList()
            }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every allow-listed tool still needs its entry`() {
        val stale = mt5ToolAllowList.filterNot { sources[it]?.contains("com.qkt.connector.mt5") == true }
        assertThat(stale).`as`("remove these from the allow-list").isEmpty()
    }

    @Test
    fun `connectors use only the shared model and the contracts`() {
        val offenders =
            sources.filterKeys { connectorOf(it) != null }.flatMap { (path, code) ->
                forbiddenForConnectors
                    .filter { Regex("com\\.qkt\\.$it\\.").containsMatchIn(code) }
                    .map { "$path -> $it" }
            }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the contracts reference no connector`() {
        val offenders = sources.filterKeys { it.startsWith("connectivity/") }.filterValues { it.contains("com.qkt.connector.") }.keys
        assertThat(offenders).isEmpty()
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests 'com.qkt.connectivity.ConnectivityArchitectureTest' -Pkotlin.compiler.execution.strategy=daemon`
Expected: PASS. If it fails, the message names the file and dependency: fix the dependency (move the code, or use the contract) — never widen the allow-list for live-path files.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/connectivity/ConnectivityArchitectureTest.kt
git commit -m "test(broker): enforce connector dependency direction"
```

---

### Task 9: Documentation and full verification

**Files:**
- Create: `docs/concepts/trading-accounts.md` (the real-world model table, contracts, how to add a connector)
- Modify: the `brokers:` section of the config reference (find it with `rg -l "gateway_url" docs/reference docs/how-to`) — add the Bybit entry and the "type is required" rule
- Modify: `docs/research/2026-09-18-venue-plugin-architecture.md` status line → "Step 1a implemented on `refactor/venue-contracts`"

- [ ] **Step 1: Write the docs** (plain language; the spec's naming table is the backbone; include the "add a connector" checklist: package, `Connector`, services line, `AccountConfig` fields, tests, architecture test green).

- [ ] **Step 2: Full verification**

```bash
./gradlew build -Pkotlin.compiler.execution.strategy=daemon
git status
git log --oneline origin/dev..HEAD
rg -n 'TODO|FIXME|XXX' src/ || true
```

Expected: BUILD SUCCESSFUL (ktlint, compile, all tests); clean tree; the commits of Tasks 1–9; no new TODOs.

- [ ] **Step 3: Live smoke on the local Exness demo** (read-only daemon start, no orders)

Start the built daemon against a temp config with one `type: mt5` account pointing at `http://127.0.0.1:5001` (`expected_account_login: 436804390`) and an empty `--load-dir`; confirm the startup log shows the same `mt5 broker profiles loaded:` / `mt5 account: … login=436804390 …` lines as the `dev` build, then stop it. Repeat once with a bogus `expected_account_login` and confirm it exits with `broker account preflight failed` and code 2 (`USER_ERROR`).

- [ ] **Step 4: Commit docs**

```bash
git add docs
git commit -m "docs: explain trading accounts and connectors"
```
