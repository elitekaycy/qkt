package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A priced close acknowledgement is booked even when the deal-history read behind its costs is garbage. */
class MT5CloseFillLookupFailureTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(1_800_000_000_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val scheduler = MT5UnknownResolveScheduler(profile.name, periodicResolveMs = 3_600_000L)
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()

    @AfterEach
    fun stop() {
        scheduler.shutdownNow()
        gateway.close()
    }

    @Test
    fun `an unreadable deal history books the fill with no venue costs`() {
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        gateway.on("/history_deals_get", "this is not json")
        val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)
        val truth = MT5CloseVenueTruth(client, clock, books, null, retryBackoffMs = 0L)
        val closeFill =
            MT5AcknowledgedCloseFill(
                profile,
                bus,
                clock,
                books,
                MT5EngineCloseMarkers(profile, clock),
                scheduler,
                truth,
            )
        val request =
            OrderRequest.Market("close-1", "EXNESS:XAUUSD", Side.SELL, BigDecimal("0.10"), TimeInForce.GTC, 1L, "gold")

        closeFill.book(
            MT5AcknowledgedCloseFill.AcknowledgedClose(
                request = request,
                ticket = 42L,
                brokerSymbol = "XAUUSDm",
                ack = MT5OrderResult(10009, 900L, 7001L, BigDecimal("2401"), "", BigDecimal("0.10")),
                filledQuantity = BigDecimal("0.10"),
                closeStartedAtMs = 1L,
                positionClosed = true,
            ),
        )

        val fill = fills.single()
        assertThat(fill.price).isEqualByComparingTo("2401")
        assertThat(fill.venueCosts).isEqualByComparingTo("0")
    }
}
