package com.qkt.observe.insights

import com.qkt.dsl.StrategySource
import com.qkt.dsl.ast.StreamDecl
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionRight
import com.qkt.instrument.OptionTerms
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyStartedFactsTest {
    private fun meta(
        symbol: String,
        category: String? = null,
        derivative: com.qkt.instrument.DerivativeTerms? = null,
    ) = InstrumentMeta(
        qktSymbol = symbol,
        contractSize = BigDecimal.ONE,
        volumeStep = BigDecimal("0.01"),
        volumeMin = BigDecimal("0.01"),
        volumeMax = null,
        pointSize = BigDecimal("0.01"),
        digits = 2,
        tradeStopsLevelPoints = 0,
        currency = "USD",
        derivative = derivative,
        category = category,
    )

    private val option =
        OptionTerms(
            root = "DERIBIT:BTC_USDC",
            underlyingIndex = "btc_usdc",
            strike = BigDecimal("70000"),
            right = OptionRight.CALL,
            expiryMs = 1_796_025_600_000L,
            tickSteps = TickSteps(BigDecimal("5")),
        )

    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                when (qktSymbol) {
                    "EXNESS:AAPL" -> meta(qktSymbol, category = "Stocks")
                    "DERIBIT:BTC-29NOV26-70000-C" -> meta(qktSymbol, derivative = option)
                    "DERIBIT:BTC-PERPETUAL" ->
                        meta(qktSymbol, derivative = FutureTerms("DERIBIT:BTC", null, perpetual = true))
                    else -> null
                }
        }

    @Test
    fun `source facts name the file, its sha, its text and whether it was cut`() {
        val source = StrategySource.of(Path.of("/srv/qkt/gold.qkt"), "STRATEGY gold VERSION 1")

        assertThat(sourceFacts(source)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "sourcePath" to "/srv/qkt/gold.qkt",
                "sourceSha256" to source.sha256,
                "sourceText" to "STRATEGY gold VERSION 1",
                "sourceTruncated" to false,
            ),
        )
        assertThat(sourceFacts(null)).isEmpty()
    }

    @Test
    fun `stream facts state each stream as the deploy wrote it`() {
        val streams = streamFacts(listOf(StreamDecl("a", "EXNESS", "AAPL", "1h", 50)))

        assertThat(streams.single()).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "alias" to "a",
                "broker" to "EXNESS",
                "symbol" to "AAPL",
                "qktSymbol" to "EXNESS:AAPL",
                "timeframe" to "1h",
                "warmupBars" to 50,
            ),
        )
    }

    @Test
    fun `each stream gains what its instrument is, when qkt knows it`() {
        val venues = InsightsVenues(instruments = registry)
        val metadata =
            mapOf(
                "deployName" to "book",
                "streams" to
                    listOf(
                        mapOf("alias" to "a", "qktSymbol" to "EXNESS:AAPL"),
                        mapOf("alias" to "b", "qktSymbol" to "DERIBIT:BTC-29NOV26-70000-C"),
                        mapOf("alias" to "c", "qktSymbol" to "UNKNOWN:X"),
                    ),
            )

        @Suppress("UNCHECKED_CAST")
        val streams = venues.withStreamInstruments(metadata)["streams"] as List<Map<String, Any?>>

        assertThat(streams[0]["instrument"] as Map<String, Any?>).containsEntry("category", "Stocks")
        assertThat(streams[1]["instrument"] as Map<String, Any?>)
            .containsEntry("kind", "option")
            .containsEntry("strike", BigDecimal("70000"))
            .containsEntry("optionRight", "call")
            .containsEntry("expiry", 1_796_025_600_000L)
            .containsEntry("underlying", "btc_usdc")
        assertThat(streams[2]).doesNotContainKey("instrument")
        assertThat(venues.withStreamInstruments(mapOf("deployName" to "x"))).isEqualTo(mapOf("deployName" to "x"))
    }

    @Test
    fun `a perpetual states no expiry`() {
        val facts = InsightsVenues(instruments = registry).instrument("DERIBIT:BTC-PERPETUAL")!!

        assertThat(facts).containsEntry("kind", "perpetual").containsEntry("root", "DERIBIT:BTC")
        assertThat(facts["expiry"]).isNull()
    }
}
