package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Symbol metadata read from a fake gateway: every missing, null and unparseable field. */
class MT5SymbolInfoReadsTest {
    private val gateway = Mt5RoutedGateway()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    @AfterEach
    fun stop() = gateway.close()

    private fun reads(cache: MT5ReadCache? = null): MT5AccountReads =
        MT5AccountReads(
            http,
            gateway.url,
            null,
            json,
            MT5GatewayReads(http, gateway.url, null, 0, cache),
            MT5SnapshotParser(json, MT5VenueTime(MT5ServerTimeZone.UTC)),
            cache,
        )

    private fun allKeys(
        keys: List<String>,
        literal: String,
    ): String = keys.joinToString(",", "{", "}") { "\"$it\":$literal" }

    @Test
    fun `a symbol the gateway describes fully is read field by field`() {
        gateway.on(
            "/symbol_info/XAUUSDm",
            """{"ask":"2401.5","bid":"2401.1","digits":3,"point":"0.001","trade_tick_size":"0.01",
            "trade_stops_level":20,"trade_freeze_level":5,"volume_min":"0.01","volume_step":"0.01","volume_max":"200",
            "trade_contract_size":"100","swap_mode":1,"swap_long":"-3.2","swap_short":"1.1","swap_rollover3days":3,
            "path":"Standard\\Metals\\XAUUSDm","trade_mode":4}""",
        )

        val info = reads().getSymbolInfo("XAUUSDm")!!

        assertThat(info).isEqualTo(
            MT5SymbolInfo(
                ask = BigDecimal("2401.5"),
                bid = BigDecimal("2401.1"),
                digits = 3,
                point = BigDecimal("0.001"),
                tradeStopsLevel = 20,
                volumeMin = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                contractSize = BigDecimal("100"),
                volumeMax = BigDecimal("200"),
                tradeFreezeLevel = 5,
                swap = MT5SymbolSwap(1, BigDecimal("-3.2"), BigDecimal("1.1"), 3),
                tickSize = BigDecimal("0.01"),
                path = "Standard\\Metals\\XAUUSDm",
                tradeMode = 4,
            ),
        )
    }

    @Test
    fun `symbol fields that are missing null or unparseable fall back to their defaults`() {
        val keys =
            listOf(
                "ask",
                "bid",
                "digits",
                "point",
                "trade_tick_size",
                "trade_stops_level",
                "trade_freeze_level",
                "volume_min",
                "volume_step",
                "volume_max",
                "trade_contract_size",
                "swap_mode",
                "swap_long",
                "swap_short",
                "swap_rollover3days",
                "path",
                "trade_mode",
            )
        gateway.on(
            "/symbol_info/A",
            "{}",
            allKeys(keys, "null"),
            allKeys(keys - "path", "\"junk\""),
            allKeys(keys, "0"),
        )
        val reads = reads()
        val blank =
            MT5SymbolInfo(
                ask = BigDecimal.ZERO,
                bid = BigDecimal.ZERO,
                digits = 0,
                point = BigDecimal.ZERO,
                tradeStopsLevel = 0,
                volumeMin = BigDecimal.ZERO,
                volumeStep = BigDecimal.ZERO,
                contractSize = BigDecimal.ONE,
            )

        assertThat(List(3) { reads.getSymbolInfo("A") }).containsOnly(blank)
        val zeroes = reads.getSymbolInfo("A")!!
        assertThat(zeroes.tickSize).`as`("a zero tick size is not a tick size").isNull()
        assertThat(zeroes.volumeMax).`as`("a zero volume cap is no cap").isNull()
        assertThat(zeroes.swap).isEqualTo(MT5SymbolSwap(0, BigDecimal.ZERO, BigDecimal.ZERO, 0))
        assertThat(reads.getSymbolInfo("UNLISTED")).isNull()
    }

    @Test
    fun `swap terms are absent unless the gateway reports all four`() {
        val full =
            mapOf(
                "swap_mode" to "1",
                "swap_long" to "\"-1\"",
                "swap_short" to "\"2\"",
                "swap_rollover3days" to "3",
            )
        val partials =
            full.keys.map { dropped ->
                (full - dropped).entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
            }
        gateway.on("/symbol_info/A", *partials.toTypedArray())
        val reads = reads()

        assertThat(List(4) { reads.getSymbolInfo("A")!!.swap }).containsOnlyNulls()
    }
}
