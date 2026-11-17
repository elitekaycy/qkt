package com.qkt.cli

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoadBranchesTest {
    private val dir: Path = createTempDirectory("cfg")

    private fun load(yaml: String): Config {
        val file = Files.createTempFile(dir, "qkt", ".yaml")
        Files.writeString(file, yaml)
        return Config.load(file)
    }

    private fun refusal(yaml: String) = assertFailsWith<RuntimeException> { load(yaml) }.message.orEmpty()

    @Test
    fun missingFileAndNonMappingFallBackToDefaults() {
        val missing = Config.load(dir.resolve("nope.yaml"))
        assertEquals("tv", missing.source)
        assertEquals("./data", missing.dataRoot)
        assertEquals(BigDecimal.ZERO, missing.startingBalance)
        assertNull(missing.configDir)
        assertEquals("tv", load("- a\n- b\n").source)
        assertEquals("tv", load("").source)
    }

    @Test
    fun scalarsAndFlatSections() {
        val config =
            load(
                """
                source: mt5
                data_root: /d
                starting_balance: 1500.5
                log_level: debug
                report: {dir: out}
                runtime: {mode: paper, waivers: {alerts: {reason: why}, plain: text, empty: null}}
                account: {currency: eur}
                fx_conversion: {source: feed, missing_policy: warn, symbols: {EURUSD: "BACKTEST:EURUSD", bad: null}}
                execution: {preset: realistic, seed: 4}
                promotion: {enforce: true}
                tv: {user: u, pass: null}
                state: {enabled: true}
                """.trimIndent(),
            )
        assertEquals("mt5", config.source)
        assertEquals("/d", config.dataRoot)
        assertEquals(BigDecimal("1500.5"), config.startingBalance)
        assertEquals("debug", config.logLevel)
        assertEquals("out", config.reportDir)
        assertNotNull(config.configDir)
        assertEquals(mapOf("mode" to "paper"), config.runtime)
        assertEquals(mapOf("alerts" to "why", "plain" to "text", "empty" to ""), config.runtimeWaivers)
        assertEquals(mapOf("source" to "feed", "missing_policy" to "warn"), config.fxConversion)
        assertEquals(mapOf("EURUSD" to "BACKTEST:EURUSD", "bad" to ""), config.fxConversionSymbols)
        assertEquals(mapOf("preset" to "realistic", "seed" to "4"), config.execution)
        assertEquals(mapOf("user" to "u", "pass" to ""), config.tv)
        assertEquals("true", config.promotion["enforce"])
    }

    @Test
    fun brokerBlocksAreSplitByKind() {
        val config =
            load(
                """
                fetchers: {f1: {k: v, n: null}, f2: scalar}
                brokers:
                  b1:
                    url: http://x
                    calendars:
                      "BTC*": crypto
                      "XAU*": {base: fx, pause: "17:00-18:00", zone: America/New_York}
                      "EUR*": {base: fx, pause: "17:00-18:00"}
                      "GBP*": {base: nyse}
                      "*": null
                    aliases: {GOLD: XAUUSD, X: null}
                    capability_restrictions: [stop, null]
                    instrument_overrides:
                      XAUUSD: {lot: 0.01, n: null}
                      BAD: scalar
                  b2: plain
                """.trimIndent(),
            )
        assertEquals(mapOf("k" to "v", "n" to ""), config.fetchers["f1"])
        assertEquals(emptyMap(), config.fetchers["f2"])
        assertEquals(emptyMap(), config.brokers["b2"])
        assertEquals(
            listOf(
                "BTC*" to "crypto",
                "XAU*" to "fx pause 17:00-18:00 America/New_York",
                "EUR*" to "fx pause 17:00-18:00",
                "GBP*" to "nyse",
                "*" to "",
            ),
            config.brokerCalendars["b1"],
        )
        assertNull(config.brokerCalendars["b2"])
        assertEquals(mapOf("GOLD" to "XAUUSD", "X" to ""), config.brokerAliases["b1"])
        assertEquals(listOf("stop", ""), config.brokerCapabilityRestrictions["b1"])
        assertEquals(
            mapOf("XAUUSD" to mapOf("lot" to "0.01", "n" to ""), "BAD" to emptyMap()),
            config.brokerInstrumentOverrides["b1"],
        )
    }

    @Test
    fun calendarMapNeedsBase() {
        val message = refusal("brokers:\n  b:\n    calendars:\n      A: {pause: x}\n")
        assertTrue("needs 'base'" in message, message)
    }

    @Test
    fun bookRiskParsesEverySection() {
        val config =
            load(
                """
                book_risk:
                  capital: 100000
                  limits: {max_gross_exposure: 3, max_net_exposure: 2, max_symbol_concentration: 1.5}
                  de_risk:
                    ladder:
                      - {drawdown: 0.04, factor: 0.5}
                      - {drawdown: 0.08, factor: 0, cooldown_bars: 5}
                      - {drawdown: 0.1}
                      - {factor: 1}
                      - scalar
                  allocation: {method: " inverse_vol ", target_vol: 0.1, rebalance_every_bars: 20, max_leverage: 2}
                """.trimIndent(),
            )
        val book = assertNotNull(config.bookRisk)
        assertEquals(BigDecimal("100000"), book.capital)
        assertEquals(BigDecimal("3"), book.limits?.maxGrossExposure)
        assertEquals(BigDecimal("1.5"), book.limits?.maxSymbolConcentration)
        assertEquals(2, book.deRisk?.ladder?.size)
        assertEquals(
            5,
            book.deRisk
                ?.ladder
                ?.get(1)
                ?.cooldownBars,
        )
        assertNull(
            book.deRisk
                ?.ladder
                ?.get(0)
                ?.cooldownBars,
        )
        assertEquals(com.qkt.risk.book.AllocationMethod.INVERSE_VOL, book.allocation?.method)
        assertEquals(20, book.allocation?.rebalanceEveryBars)
        assertEquals(BigDecimal("2"), book.allocation?.maxLeverage)
    }

    @Test
    fun bookRiskDefaultsAndMethods() {
        assertNull(load("book_risk: scalar\n").bookRisk)
        val bare = assertNotNull(load("book_risk: {de_risk: {ladder: []}, allocation: {}}\n").bookRisk)
        assertNull(bare.deRisk)
        assertNull(bare.limits)
        assertEquals(com.qkt.risk.book.AllocationMethod.FIXED, bare.allocation?.method)
        assertEquals(BigDecimal("4"), bare.allocation?.maxLeverage)
        assertEquals(0, bare.allocation?.rebalanceEveryBars)
        for ((raw, expected) in mapOf("ERC" to "ERC", "regime_weighted" to "REGIME_WEIGHTED", "fixed" to "FIXED")) {
            val method = load("book_risk: {allocation: {method: $raw}}\n").bookRisk?.allocation?.method
            assertEquals(expected, method?.name)
        }
        assertTrue("unknown book_risk.allocation.method" in refusal("book_risk: {allocation: {method: nope}}\n"))
    }

    @Test
    fun riskBlockValidation() {
        assertTrue("unknown risk key(s): bogus" in refusal("risk: {bogus: 1}\n"))
        assertTrue("must be a key-value block" in refusal("risk: {per_strategy: {s: 3}}\n"))
        assertTrue("unknown risk.per_strategy.s key(s)" in refusal("risk: {per_strategy: {s: {bogus: 1}}}\n"))
        assertTrue("hard_daily_loss" in refusal("risk: {hard_daily_loss: abc}\n"))
        assertTrue("must be >=" in refusal("risk: {hard_daily_loss: 5, max_daily_loss: 10}\n"))
        assertEquals(BigDecimal("10"), load("risk: {hard_daily_loss: 20, max_daily_loss: 10}\n").maxDailyLoss)
        assertEquals(emptyMap(), load("risk: scalar\n").risk)
    }

    @Test
    fun envVarsExpandWithDefaultsAndStayLiteralWhenUnset() {
        System.setProperty("QKT_TEST_CFG_PROP", "fromprop")
        try {
            val config =
                load(
                    "tv: {a: '\${QKT_TEST_CFG_PROP}', b: '\${QKT_TEST_CFG_UNSET:-dflt}', c: '\${QKT_TEST_CFG_NONE}'}\n",
                )
            assertEquals("fromprop", config.tv["a"])
            assertEquals("dflt", config.tv["b"])
            assertEquals("\${QKT_TEST_CFG_NONE}", config.tv["c"])
        } finally {
            System.clearProperty("QKT_TEST_CFG_PROP")
        }
    }
}
