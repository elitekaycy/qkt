package com.qkt.cli.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The STRATEGIES section with missing fields, routing, promotion, operator stops and audit-journal losses. */
class StrategyStatusLinesFieldsTest {
    private val unhealthy = mutableListOf<String>()
    private val halted = mutableListOf<String>()
    private val stopped = mutableListOf<String>()

    private fun render(json: String) =
        renderStrategies(Json.parseToJsonElement(json).jsonArray, unhealthy, halted, stopped)

    @Test
    fun `no deployments says so`() {
        assertThat(
            renderStrategies(Json.parseToJsonElement("[]").jsonArray, unhealthy),
        ).isEqualTo("STRATEGIES   none deployed")
        assertThat(unhealthy).isEmpty()
    }

    @Test
    fun `an entry with no fields reads as unknown and unhealthy`() {
        val screen = render("[{}]")

        assertThat(screen.lines()).containsExactly("STRATEGIES", "  ?                    ?, 0 trades, up 0s")
        assertThat(unhealthy).containsExactly("strategy '?' state=?")
    }

    @Test
    fun `a child shows its stream routing and an unknown broker as a question mark`() {
        val screen =
            render(
                """[{"name":"book/a","kind":"child","state":"running","trades":4,"uptimeMs":3723000,
                    "streamBrokers":{"gold":"exness","btc":null},"streamBrokers2":{}}]""",
            )

        assertThat(screen.lines()).containsExactly(
            "STRATEGIES",
            "  book/a               running, 4 trades, up 1h2m [child]",
            "    streams: gold→exness, btc→?",
        )
        assertThat(unhealthy).isEmpty()
    }

    @Test
    fun `promotion is shown with its eligibility, and enforced missing gates fail the health check`() {
        val screen =
            render(
                """[{"name":"a","state":"running","promotionEnforced":true,"promotionEligible":false,
                     "promotionMissingGates":["approval","paper_days"]},
                    {"name":"b","state":"running","promotionState":"paper","promotionEligible":true},
                    {"name":"c","state":"running","promotionMissingGates":["walk_forward"]},
                    {"name":"d","state":"running","streamBrokers":{}}]""",
            )

        assertThat(screen.lines().filter { "promotion:" in it }).containsExactly(
            "    promotion: none eligible=no missing=approval,paper_days",
            "    promotion: paper eligible=yes",
            "    promotion: none eligible=unknown missing=walk_forward",
        )
        assertThat(screen).doesNotContain("streams:")
        assertThat(unhealthy).containsExactly("strategy 'a' promotion gates missing: approval,paper_days")
    }

    @Test
    fun `an operator stop, a reasonless halt and a reasonless stream stop are each named`() {
        val screen =
            render(
                """[{"name":"a","state":"running","gateState":"operator_stopped","halted":true,
                     "streamStops":[{}]}]""",
            )

        assertThat(screen.lines()[1]).endsWith("[HALTED][OP_STOPPED][STREAM_STOPPED]")
        assertThat(
            screen,
        ).contains("    halted: no reason recorded").contains("    stream stopped: ?: no reason recorded")
        assertThat(unhealthy).containsExactly("strategy 'a' is operator-stopped")
        assertThat(halted).containsExactly("strategy 'a' is halted () - clear with: qkt resume a")
        assertThat(stopped).containsExactly("strategy 'a' refuses orders on ? (no reason recorded) - try: qkt resume a")
    }

    @Test
    fun `dropped market data is shown and lost decision events also fail the health check`() {
        val screen =
            render(
                """[{"name":"a","state":"running","auditJournal":{"droppedMarketData":3}},
                    {"name":"b","state":"running","auditJournal":{"droppedMarketData":0,"lostDecisionEvents":2}},
                    {"name":"c","state":"running","auditJournal":{}}]""",
            )

        assertThat(screen.lines().filter { "audit journal" in it }).containsExactly(
            "    audit journal: dropped 3 market-data record(s), lost 0 decision event(s)",
            "    audit journal: dropped 0 market-data record(s), lost 2 decision event(s)",
        )
        assertThat(unhealthy).containsExactly("strategy 'b' audit journal lost 2 decision event(s)")
    }

    @Test
    fun `uptime is compact at every scale`() {
        assertThat(formatUptime(9_000)).isEqualTo("9s")
        assertThat(formatUptime(302_000)).isEqualTo("5m2s")
        assertThat(formatUptime(15_000_000)).isEqualTo("4h10m")
        assertThat(formatUptime(183_600_000)).isEqualTo("2d3h")
    }
}
