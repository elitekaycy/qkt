package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The effective-config allow-list: named, resolved values only, with the values a session really gets. */
class EffectiveConfigFactsTest {
    private fun config(
        dir: Path,
        yaml: String,
    ): Config = Config.load(Files.writeString(dir.resolve("qkt.config.yaml"), yaml))

    private fun Map<String, Any?>.group(name: String): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        (this[name] as Map<String, Any?>)

    private fun Map<String, Any?>.field(name: String): Map<String, Any?> = group(name)

    private val facts = { cfg: Config -> EffectiveConfigFacts(cfg, connectorTypes = setOf("mt5", "gateway")) }

    @Test
    fun `an empty config states its defaults as defaults and an unset limit with no value`(
        @TempDir dir: Path,
    ) {
        val out = facts(config(dir, "source: tv\n")).standalone(listOf("BACKTEST:BTCUSDT"), null)

        assertThat(out.keys.toList()).containsExactly(
            "schema",
            "kind",
            "runtimeMode",
            "accounts",
            "currency",
            "startingBalance",
            "risk",
            "orders",
            "marketData",
        )
        assertThat(out["kind"]).isEqualTo("standalone")
        assertThat(out.group("currency")).isEqualTo(mapOf("value" to "USD", "source" to "default"))
        assertThat(out.group("startingBalance")["value"]).isNull()
        val risk = out.group("risk")
        assertThat(risk["appliesTo"]).isEqualTo("strategy")
        assertThat(risk.field("maxDailyLoss")["source"]).isEqualTo("default")
        assertThat(risk.field("maxDrawdownFraction")["value"]).isNull()
        assertThat(out.group("orders").field("measuredUsageHours")).containsEntry("value", 24L)
    }

    @Test
    fun `a limit set to zero is off, not a limit of zero`(
        @TempDir dir: Path,
    ) {
        val yaml =
            """
            risk:
              max_daily_loss: "0"
              margin_floor_pct: "0"
              measured_usage_hours: 0
              max_round_trips_10m: 0
              max_broker_rejections_1m: 0
            """.trimIndent()
        val out = facts(config(dir, yaml)).standalone(emptyList(), null)
        val orders = out.group("orders")

        assertThat(out.group("risk").field("maxDailyLoss")).isEqualTo(mapOf("value" to null, "source" to "configured"))
        for (key in listOf(
            "marginFloorPct",
            "measuredUsageHours",
            "measuredUsageMaxQty",
            "runawayMaxRoundTrips10m",
            "runawayMaxBrokerRejections1m",
        )) {
            assertThat(orders.field(key)["value"]).describedAs(key).isNull()
        }
    }

    @Test
    fun `only this strategy's overrides are stated, and only the ones it set`(
        @TempDir dir: Path,
    ) {
        val yaml =
            """
            risk:
              per_strategy:
                gold: { max_daily_loss: "500", max_open_positions: 3 }
                silver: { max_daily_loss: "77" }
            """.trimIndent()
        val cfg = config(dir, yaml)
        val out = facts(cfg).standalone(emptyList(), cfg.perStrategyRisk["gold"])

        val strategy = out.group("strategyRisk")
        assertThat(strategy.keys).containsExactlyInAnyOrder("maxDailyLoss", "maxOpenPositions")
        assertThat(strategy.field("maxOpenPositions")).isEqualTo(mapOf("value" to 3, "source" to "configured"))
        assertThat(out.toString()).doesNotContain("77")
        assertThat(facts(cfg).standalone(emptyList(), null)).doesNotContainKey("strategyRisk")
    }

    @Test
    fun `an account is named only when a stream trades it and its type is an installed connector`(
        @TempDir dir: Path,
    ) {
        val yaml =
            """
            brokers:
              exness: { type: mt5 }
              other: { type: mt5 }
              odd: { type: not-a-connector }
            """.trimIndent()
        val out = facts(config(dir, yaml)).standalone(listOf("EXNESS:XAUUSD", "EXNESS:EURUSD", "ODD:X", "HUB:Y"), null)

        assertThat(out["accounts"]).isEqualTo(
            listOf(mapOf("name" to "EXNESS", "type" to "mt5"), mapOf("name" to "ODD")),
        )
    }

    @Test
    fun `a currency that cannot be one is not stated`(
        @TempDir dir: Path,
    ) {
        val out = facts(config(dir, "account: { currency: 'tok-en_123456' }\n")).standalone(emptyList(), null)

        assertThat(out.group("currency")).isEqualTo(mapOf("value" to null, "source" to "configured"))
    }

    @Test
    fun `a portfolio child states its book, whose caps and basis are the book's`(
        @TempDir dir: Path,
    ) {
        val yaml =
            """
            risk: { max_drawdown_pct: "6" }
            book_risk:
              capital: "50000"
              limits: { max_gross_exposure: "3.0", max_symbol_concentration: "0.5" }
              de_risk: { ladder: [{ drawdown: "0.03", factor: "0.5" }] }
            """.trimIndent()
        val cfg = config(dir, yaml)
        val out =
            facts(cfg).portfolioChild(
                symbols = emptyList(),
                override = null,
                portfolioName = "weighted_book",
                portfolioCapital = "100000".toBigDecimal(),
                weight = "0.6".toBigDecimal(),
                allocatedCapital = "30000".toBigDecimal(),
                allocates = false,
            )
        val book = out.group("book")

        assertThat(out).doesNotContainKey("startingBalance")
        assertThat(out["kind"]).isEqualTo("portfolio_child")
        assertThat(out.group("risk")["appliesTo"]).isEqualTo("book")
        assertThat(book["name"]).isEqualTo("weighted_book")
        assertThat(book.field("capital")).isEqualTo(mapOf("value" to "50000".toBigDecimal(), "source" to "configured"))
        assertThat(book["weight"]).isEqualTo("0.6".toBigDecimal())
        assertThat(book["allocatedCapital"]).isEqualTo("30000".toBigDecimal())
        assertThat(book.field("maxGrossExposure")["value"]).isEqualTo("3.0".toBigDecimal())
        assertThat(book.field("maxNetExposure")["value"]).isNull()
        assertThat(book["deRiskSteps"]).isEqualTo(
            listOf(
                mapOf(
                    "drawdown" to "0.03".toBigDecimal(),
                    "factor" to "0.5".toBigDecimal(),
                ),
            ),
        )
    }
}
