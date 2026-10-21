package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * `qkt bot close` on a gateway reports what the venue did. Live, 2026-10-21: the paper gateway filled a
 * close under an order it named itself (`close.mvigikbv.1`), and the CLI reported `lost order` for the id
 * it had sent. A close whose answer never comes is decided by the venue's fills and position.
 */
class GatewayDirectCloseTest {
    private val perp = "SOL_USDC-PERPETUAL"
    private val fake =
        FakeGateway(listOf(perp)).apply {
            instruments[perp] = WireInstrument(perp, "perpetual", "USDC", "1", "0.001", "0.1", "0.1")
        }
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 2_000, retryAttempts = 1)
    private val venue =
        GatewayDirectVenue("deribit", "DERIBIT:", client, fake.url, "k", pollTimeoutMs = 300, pollMs = 20)

    @AfterEach
    fun stop() {
        namesItsCloses -= fake
        fake.shutdown()
    }

    private fun hold() {
        fake.act {
            place(WireSubmit("seed.1", perp, "buy", "market", "2", null, null, "gtc", false))
            fill("seed.1", "f-seed", "2", "105.5", System.currentTimeMillis() - 1_000)
        }
    }

    @Test
    fun `a close the gateway names itself is settled under its name, not reported lost`() {
        hold()
        namesItsCloses += fake

        val result = venue.close(venue.positions()!!.single(), null, "bot-close-1")

        assertThat(result.ok).isTrue
        assertThat(result.error).isNull()
        assertThat(result.ticket).isEqualTo("close.1.1")
        assertThat(result.price).isEqualByComparingTo(CLOSE_PRICE)
        assertThat(venue.positions()).isEmpty()
    }

    @Test
    fun `a close whose answer was lost is decided by the fill that closed the position`() {
        hold()
        val position = venue.positions()!!.single()
        // The venue took the close, but no answer reaches qkt.
        fake.act {
            place(WireSubmit("close.9.1", perp, "sell", "market", "2", null, null, "ioc", reduceOnly = true))
            fill("close.9.1", "f-close", "2", "104", System.currentTimeMillis())
        }
        fake.failing["/v1/positions/close"] = 1_000

        val result = venue.close(position, null, "bot-close-1")

        assertThat(result.ok).isTrue
        assertThat(result.state).isEqualTo("filled")
        assertThat(result.deal).isEqualTo("f-close")
        assertThat(result.price).isEqualByComparingTo("104")
    }

    @Test
    fun `a close the venue never took is reported, with what it still holds`() {
        hold()
        fake.failing["/v1/positions/close"] = 1_000

        val result = venue.close(venue.positions()!!.single(), null, "bot-close-1")

        assertThat(result.ok).isFalse
        assertThat(result.error).contains("close unanswered").contains("holds 2")
    }

    @Test
    fun `a market order is still settled when the gateway answers with the order`() {
        val result =
            venue.place(
                OrderRequest.Market(
                    "bot-a-1",
                    "DERIBIT:SOL_USDC_PERPETUAL",
                    Side.BUY,
                    BigDecimal("2"),
                    TimeInForce.GTC,
                    1L,
                ),
                null,
                null,
                "bot-a-1",
            )

        assertThat(result.ticket).isEqualTo(fake.submits.single().clientOrderId)
    }
}
