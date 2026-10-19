package com.qkt.trade.session

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What the agent is told about its intents: fates, and the events since its previous `next`. */
class BotIntentFatesTest : IntentSessionFixture() {
    @Test
    fun `risk rejection reaches the intent fate with the rule`() {
        val s = session(listOf(2400, 2401, 2402))
        s.next(gold)
        // 5 lots of a 100-oz contract at 2,400 is 1.2M, over the default 250k notional cap.
        s.submit("brain", intent("BUY x SIZING 5"))

        s.drain()

        val fate = s.fates().single()
        assertThat(fate.state).isEqualTo("rejected")
        assertThat(fate.reason).containsIgnoringCase("notional")
        assertThat(fate.orderIds).hasSize(1)
    }

    @Test
    fun `a rejected entry nested in a timed bracket is the intent's fate`() {
        val s = session(listOf(2400, 2401, 2402))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 5 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 } EXIT AFTER 2m"))

        s.drain()

        assertThat(s.fates().single().state).isEqualTo("rejected")
        assertThat(s.events().single { it.kind == "rejected" }.intentId).isEqualTo("int-1")
    }

    @Test
    fun `every id of a nested request is linked to its intent`() {
        val entry =
            com.qkt.execution.OrderRequest.Market(
                "e",
                gold,
                com.qkt.common.Side.BUY,
                java.math.BigDecimal.ONE,
                com.qkt.execution.TimeInForce.GTC,
                1L,
            )
        val bracket =
            com.qkt.execution.OrderRequest.Bracket(
                "b",
                gold,
                com.qkt.common.Side.BUY,
                java.math.BigDecimal.ONE,
                entry,
                java.math.BigDecimal("2410"),
                com.qkt.execution.StopLossSpec
                    .Fixed(java.math.BigDecimal("2390")),
                com.qkt.execution.TimeInForce.GTC,
                1L,
            )
        val timed =
            com.qkt.dsl.compile
                .timedExit("b-exit", bracket, 120_000L, 1L)

        assertThat(requestIds(timed)).containsExactlyInAnyOrder("b-exit", "b", "e")
    }

    @Test
    fun `a filled entry is the fate and next carries the fill`() {
        val s = session(listOf(2400, 2401, 2402, 2403))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1"))

        s.next(gold)
        val events = s.events()

        assertThat(s.fates().single().state).isEqualTo("filled")
        val fill = events.single { it.kind == "fill" }
        assertThat(fill.intentId).isEqualTo("int-1")
        assertThat(fill.identity).isEqualTo("brain")
        assertThat(fill.side).isEqualTo("BUY")
        assertThat(fill.quantity).isEqualByComparingTo("0.1")
        assertThat(s.events()).isEmpty()
    }

    @Test
    fun `a stop exit is reported with its reason`() {
        val s = session(listOf(2400, 2401, 2390, 2390))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 }"))

        s.drain()

        val all = s.events()
        val exit = all.filter { it.kind == "fill" }.last()
        assertThat(exit.exitReason).withFailMessage("%s", all.joinToString("\n")).isNotNull()
        assertThat(exit.side).isEqualTo("SELL")
        assertThat(exit.exitReason).isEqualTo("STOP")
    }

    @Test
    fun `a timed exit's close is reported as a close`() {
        val s = session(listOf(2400, 2401, 2402, 2403, 2404, 2405))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1 EXIT AFTER 2m"))

        s.drain()

        val ev = s.events()
        assertThat(
            ev.last { it.kind == "fill" }.exitReason,
        ).withFailMessage("%s", ev.joinToString("\n")).isEqualTo("CLOSE")
    }

    @Test
    fun `a working limit is listed until it fills`() {
        val s = session(listOf(2400, 2401, 2402, 2380, 2380))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1 ORDER_TYPE = LIMIT AT 2390"))
        s.next(gold)

        assertThat(s.workingOrders().map { it.intentId }).containsExactly("int-1")

        s.drain()
        assertThat(s.workingOrders()).isEmpty()
    }

    @Test
    fun `positions list each identity's legs`() {
        val s = session(listOf(2400, 2401, 2402), identities = listOf("brain", "other"))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1"))
        s.submit("other", intent("SELL x SIZING 0.2"))
        s.next(gold)

        val legs = s.legs()

        assertThat(legs.map { it.identity to it.side }).containsExactlyInAnyOrder("brain" to "BUY", "other" to "SELL")
    }
}
