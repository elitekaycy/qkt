package com.qkt.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.MarginLevelProvider
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.risk.rules.MarginFloor
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** An operator who set a margin floor is told at startup which venues it cannot protect. */
class EntryGuardRulesMarginWarningTest {
    private open class Venue(
        override val name: String,
    ) : Broker {
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: com.qkt.execution.OrderRequest) = error("not used")

        override fun cancel(orderId: String) = error("not used")

        override fun modify(
            orderId: String,
            changes: com.qkt.broker.OrderModification,
        ) = error("not used")
    }

    private class MarginVenue(
        name: String,
    ) : Venue(name),
        MarginLevelProvider {
        override val supportsMarginLevel = true

        override fun marginLevel(): BigDecimal = error("startup must not read the margin level")
    }

    private val logger = LoggerFactory.getLogger(LiveSession::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun warnings() =
        appender.list.filter { it.level == Level.WARN && "margin_floor_pct" in it.formattedMessage }.map {
            it.formattedMessage
        }

    private fun guard(floor: String) = EntryGuardRules(FixedClock(0L), BigDecimal(floor), 0L, BigDecimal.ONE)

    private fun routed(vararg routes: Pair<String, Broker>) =
        CompositeBroker(routes.map { (symbol, venue) -> SymbolPattern.exactSet(setOf(symbol)) to venue })

    @Test
    fun `warns once for a venue that reports no margin, naming every symbol the floor does not cover`() {
        val venue = Venue("PAPER_DERIBIT")

        val rules =
            guard(
                "200",
            ).rules(
                routed("PAPER_DERIBIT:A" to venue, "PAPER_DERIBIT:B" to venue),
                listOf("PAPER_DERIBIT:A", "PAPER_DERIBIT:B"),
            )

        val warning = warnings().single()
        assertThat(warning).contains("200", "PAPER_DERIBIT:A", "PAPER_DERIBIT:B", "venue PAPER_DERIBIT")
        assertThat(rules.filterIsInstance<MarginFloor>()).hasSize(1)
    }

    @Test
    fun `warns once per venue, and not for the venue that reports margin`() {
        val broker =
            routed("X:A" to Venue("X"), "Y:A" to Venue("Y"), "MT5:A" to MarginVenue("MT5"))

        guard("200").rules(broker, listOf("X:A", "Y:A", "MT5:A"))

        assertThat(warnings()).hasSize(2)
        assertThat(warnings().joinToString()).contains("venue X", "venue Y").doesNotContain("MT5")
    }

    @Test
    fun `says nothing when every venue reports margin`() {
        guard("200").rules(MarginVenue("MT5"), listOf("MT5:A"))

        assertThat(warnings()).isEmpty()
    }

    @Test
    fun `says nothing when the floor is off`() {
        guard("0").rules(Venue("X"), listOf("X:A"))

        assertThat(warnings()).isEmpty()
    }
}
