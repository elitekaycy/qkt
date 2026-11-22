package com.qkt.cli

import com.qkt.app.LiveEquityBasis
import com.qkt.cli.shim.Test
import com.qkt.cli.shim.assertEquals
import com.qkt.cli.shim.assertFailsWith
import com.qkt.cli.shim.assertNull
import com.qkt.cli.shim.assertTrue
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import java.math.BigDecimal
import java.nio.file.Path

class ConfigGettersBranchesTest {
    private fun risk(vararg pairs: Pair<String, String>) = Config(risk = mapOf(*pairs))

    private fun state(vararg pairs: Pair<String, String>) = Config(state = mapOf(*pairs))

    @Test
    fun accountAndAccountingDefaultsAndOverrides() {
        assertEquals("USD", Config().accountCurrency)
        assertEquals("USD", Config(account = mapOf("currency" to " ")).accountCurrency)
        assertEquals("EUR", Config(account = mapOf("currency" to "eur")).accountCurrency)
        val accounting =
            Config(
                account = mapOf("currency" to "gbp"),
                fxConversion = mapOf("source" to "feed", "missing_policy" to "warn"),
                fxConversionSymbols = mapOf("USDJPY" to "BACKTEST:USDJPY"),
            ).accountingConfig
        assertEquals("feed", accounting.source)
        assertEquals(com.qkt.accounting.FxMissingPolicy.WARN, accounting.missingPolicy)
        assertEquals(
            com.qkt.accounting.FxMissingPolicy.FAIL,
            Config(fxConversion = mapOf("missing_policy" to "Strict")).accountingConfig.missingPolicy,
        )
        assertFailsWith<IllegalStateException> {
            Config(
                fxConversion = mapOf("missing_policy" to "x"),
            ).accountingConfig
        }
        assertEquals(mapOf("USDJPY" to "BACKTEST:USDJPY"), accounting.symbols)
        assertEquals("market", Config(fxConversion = mapOf("source" to " ")).accountingConfig.source)
        assertEquals("market", Config().accountingConfig.source)
    }

    @Test
    fun runtimeModeAndWaivers() {
        assertEquals(RuntimeMode.DEV, Config().runtimeMode)
        assertEquals(RuntimeMode.PRODUCTION, Config(runtime = mapOf("mode" to "Production")).runtimeMode)
        assertFailsWith<IllegalStateException> { Config(runtime = mapOf("mode" to "x")).runtimeMode }
        val config = Config(runtimeWaivers = mapOf("alerts" to "ok", "blank" to " "))
        assertEquals("ok", config.runtimeWaiver("alerts"))
        assertNull(config.runtimeWaiver("blank"))
        assertNull(config.runtimeWaiver("missing"))
    }

    @Test
    fun riskDefaultsAndOverrides() {
        val d = Config()
        assertEquals(Config.DEFAULT_MAX_DAILY_LOSS, d.maxDailyLoss)
        assertEquals(Config.DEFAULT_MAX_ORDER_QTY, d.maxOrderQty)
        assertEquals(Config.DEFAULT_MAX_ORDER_NOTIONAL, d.maxOrderNotional)
        assertEquals(Config.DEFAULT_PRICE_COLLAR_FRAC, d.priceCollarFrac)
        assertEquals(BigDecimal("200"), d.marginFloorPct)
        assertEquals(24L, d.measuredUsageHours)
        assertEquals(BigDecimal("0.01"), d.measuredUsageMaxQty)
        assertNull(d.maxDrawdownPct)
        assertNull(d.maxDailyDrawdownPct)
        assertEquals(DrawdownBasis.STATIC, d.totalDdBasis)
        assertEquals(DailyDrawdownBasis.BALANCE, d.dailyDdBasis)
        assertEquals(LiveEquityBasis.VENUE, d.liveEquityBasis)
        val o =
            risk(
                "max_daily_loss" to "5",
                "max_order_qty" to "2",
                "max_order_notional" to "3",
                "price_collar_pct" to "10",
                "margin_floor_pct" to "0",
                "measured_usage_hours" to "0",
                "measured_usage_max_qty" to "0.5",
                "max_drawdown_pct" to "8",
                "max_daily_drawdown_pct" to "4",
                "total_dd_basis" to "trailing",
                "daily_dd_basis" to "equity",
                "live_equity_basis" to "modeled",
            )
        assertEquals(BigDecimal("5"), o.maxDailyLoss)
        assertEquals(BigDecimal("2"), o.maxOrderQty)
        assertEquals(BigDecimal("3"), o.maxOrderNotional)
        assertEquals(0, BigDecimal("0.1").compareTo(o.priceCollarFrac))
        assertEquals(BigDecimal("0"), o.marginFloorPct)
        assertEquals(0L, o.measuredUsageHours)
        assertEquals(BigDecimal("0.5"), o.measuredUsageMaxQty)
        assertEquals(0, BigDecimal("0.08").compareTo(o.maxDrawdownPct))
        assertEquals(0, BigDecimal("0.04").compareTo(o.maxDailyDrawdownPct))
        assertEquals(DrawdownBasis.TRAILING, o.totalDdBasis)
        assertEquals(DailyDrawdownBasis.EQUITY, o.dailyDdBasis)
        assertEquals(LiveEquityBasis.MODELED, o.liveEquityBasis)
        assertEquals(24L, risk("measured_usage_hours" to "x").measuredUsageHours)
    }

    @Test
    fun runawayLimitsParseOrRefuse() {
        assertEquals(7, risk("max_round_trips_10m" to "7").runawayMaxRoundTrips)
        assertEquals(0, risk("max_broker_rejections_1m" to "0").runawayMaxRejections)
        assertTrue(Config().runawayMaxRoundTrips > 0 && Config().runawayMaxRejections > 0)
        assertEquals(
            "risk.max_round_trips_10m must be a non-negative integer",
            assertFailsWith<IllegalArgumentException> {
                risk(
                    "max_round_trips_10m" to "-1",
                ).runawayMaxRoundTrips
            }.message,
        )
        assertFailsWith<IllegalArgumentException> { risk("max_broker_rejections_1m" to "x").runawayMaxRejections }
    }

    @Test
    fun pctFractionBounds() {
        assertNull(Config.pctFraction(null))
        assertEquals(0, BigDecimal("1").compareTo(Config.pctFraction("100")))
        assertFailsWith<IllegalArgumentException> { Config.pctFraction("0") }
        assertFailsWith<IllegalArgumentException> { Config.pctFraction("100.5") }
        assertFailsWith<IllegalArgumentException> { Config.pctFraction("-3") }
    }

    @Test
    fun stateFlags() {
        assertTrue(Config().stateEnabled)
        assertEquals(false, state("enabled" to "FALSE").stateEnabled)
        assertTrue(state("enabled" to "yes").stateEnabled)
        assertEquals(false, Config().stateAsync)
        assertTrue(state("async" to "True").stateAsync)
        assertEquals(false, state("async" to "no").stateAsync)
        assertTrue(
            Config(
                state = mapOf("enabled" to "false"),
            ).statePersistor(Path.of("/nonexistent")) is com.qkt.persistence.NoopStatePersistor,
        )
    }

    @Test
    fun stateIntegersDefaultParseAndRefuse() {
        val c = Config()
        assertEquals(Config.DEFAULT_JOURNAL_RETENTION_DAYS, c.journalRetentionDays)
        assertEquals(Config.DEFAULT_JOURNAL_COMPRESS_AFTER_DAYS, c.journalCompressAfterDays)
        assertEquals(Config.DEFAULT_DISK_FREE_ALERT_GB, c.diskFreeAlertGb)
        assertEquals(0, state("journal_retention_days" to "0").journalRetentionDays)
        assertEquals(3, state("journal_compress_after_days" to "3").journalCompressAfterDays)
        assertEquals(5, state("disk_free_alert_gb" to "5").diskFreeAlertGb)
        val msg =
            assertFailsWith<IllegalArgumentException> { state("journal_retention_days" to "-1").journalRetentionDays }
        assertTrue("got '-1'" in msg.message.orEmpty())
        assertFailsWith<IllegalArgumentException> {
            state(
                "journal_compress_after_days" to "x",
            ).journalCompressAfterDays
        }
        assertFailsWith<IllegalArgumentException> { state("disk_free_alert_gb" to "1.5").diskFreeAlertGb }
    }

    @Test
    fun candleCloseGrace() {
        fun grace(raw: String) = Config(runtime = mapOf("candle_close_grace_ms" to raw)).candleCloseGraceMs
        assertEquals(com.qkt.app.LiveSession.DEFAULT_CANDLE_CLOSE_GRACE_MS, Config().candleCloseGraceMs)
        assertEquals(250L, grace(" 250 "))
        assertEquals(0L, grace("0"))
        assertFailsWith<IllegalArgumentException> { grace("-1") }
        assertFailsWith<IllegalArgumentException> { grace("abc") }
    }

    @Test
    fun haltScopeAndDurations() {
        assertEquals(com.qkt.risk.HaltScope.PERSISTENT, Config.parseHaltScope(null))
        assertEquals(com.qkt.risk.HaltScope.PERSISTENT, Config.parseHaltScope(" "))
        assertEquals(com.qkt.risk.HaltScope.PERSISTENT, Config.parseHaltScope("Persistent"))
        assertEquals(com.qkt.risk.HaltScope.DAILY, Config.parseHaltScope("DAILY"))
        assertFailsWith<IllegalArgumentException> { Config.parseHaltScope("weekly") }
        assertEquals(1500L, Config.durationMs("1500"))
        assertEquals(60_000L, Config.durationMs("1m"))
    }
}
