package com.qkt.broker.bybit

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitOrderTranslatorCategoryTest {
    private fun marketRequest(symbol: String) =
        OrderRequest.Market(
            id = "c1",
            symbol = symbol,
            side = Side.BUY,
            quantity = Money.of("0.01"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    @Test
    fun `linear symbol produces category=linear and positionIdx 0`() {
        val body = BybitOrderTranslator.toCreateBody(marketRequest("BYBIT_LINEAR:BTCUSDT"))

        assertThat(body).contains("\"category\":\"linear\"")
        assertThat(body).contains("\"positionIdx\":0")
    }

    @Test
    fun `linear symbol with reduceOnly=true includes reduceOnly flag`() {
        val body = BybitOrderTranslator.toCreateBody(marketRequest("BYBIT_LINEAR:BTCUSDT"), reduceOnly = true)

        assertThat(body).contains("\"reduceOnly\":true")
    }

    @Test
    fun `linear symbol default does not include reduceOnly`() {
        val body = BybitOrderTranslator.toCreateBody(marketRequest("BYBIT_LINEAR:BTCUSDT"))

        assertThat(body).doesNotContain("reduceOnly")
    }

    @Test
    fun `spot symbol does not include positionIdx or reduceOnly`() {
        val body = BybitOrderTranslator.toCreateBody(marketRequest("BYBIT_SPOT:BTCUSDT"))

        assertThat(body).contains("\"category\":\"spot\"")
        assertThat(body).doesNotContain("positionIdx")
        assertThat(body).doesNotContain("reduceOnly")
    }
}
