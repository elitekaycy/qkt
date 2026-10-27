package com.qkt.parity.hub

import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat

/**
 * One measured strategy (docs/research, 2026-10-27) with its exact source on an hourly tape of one
 * hub field. Records land half an hour into an hour, so the hour's close is the first that sees one;
 * fills are (day, hour, side) from the start and must match on the tick, bar and live paths.
 */
internal class MeasuredStrategyScenario(
    val dataset: String,
    val scope: String,
    val field: String,
    val symbol: String,
    val days: Int,
) {
    fun assertFills(
        tmp: Path,
        strategy: String,
        records: List<Pair<Long, String?>>,
        expected: List<Triple<Int, Int, String>>,
    ) {
        val recs = records.map { (at, v) -> Rec(scope, at, mapOf(field to v)) }
        val root = HubFixtureStore(tmp).dataset(dataset, listOf(Field(field)), recs).write()
        val end = T0.plusSeconds(days * 86_400L)
        val prices = HubScenarioHarness.tape(mapOf(symbol to "100"), T0, end, stepMs = HOUR_MS)
        val runs = HubScenarioHarness.run(root, strategy.trimIndent(), prices, T0, end, window = "1h")
        val fills =
            expected.map { (d, h, side) -> HubScenarioHarness.Fill(T0.plusSeconds((d * 24L + h) * 3600), symbol, side) }
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(fills)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(fills)
        assertThat(runs.live).describedAs("live").containsExactlyElementsOf(fills)
    }

    companion object {
        val T0: Instant = Instant.parse("2024-03-05T00:00:00Z")
        const val HOUR_MS = 3_600_000L

        /** Half past [hour] on [day] of the run. */
        fun known(
            day: Int,
            hour: Int,
        ) = T0.toEpochMilli() + (day * 24L + hour) * HOUR_MS + 30 * 60_000L
    }
}
