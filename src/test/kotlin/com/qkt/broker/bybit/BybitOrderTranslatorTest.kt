package com.qkt.broker.bybit

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitOrderTranslatorTest {
    @Test
    fun `Market BUY translates to spot category market buy`() {
        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"category\":\"spot\"")
        assertThat(body).contains("\"symbol\":\"BTCUSDT\"")
        assertThat(body).contains("\"side\":\"Buy\"")
        assertThat(body).contains("\"orderType\":\"Market\"")
        assertThat(body).contains("\"orderLinkId\":\"c1\"")
        assertThat(body).contains("\"timeInForce\":\"GTC\"")
    }

    @Test
    fun `Limit translates with price`() {
        val req =
            OrderRequest.Limit(
                id = "c2",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Limit\"")
        assertThat(body).contains("\"price\":\"80000")
    }

    @Test
    fun `Stop SELL translates with triggerPrice and triggerDirection 2`() {
        val req =
            OrderRequest.Stop(
                id = "c3",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("79000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Market\"")
        assertThat(body).contains("\"triggerPrice\":\"79000")
        assertThat(body).contains("\"triggerDirection\":2")
    }

    @Test
    fun `StopLimit BUY translates with triggerPrice limitPrice and triggerDirection 1`() {
        val req =
            OrderRequest.StopLimit(
                id = "c4",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("85000"),
                limitPrice = Money.of("85100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Limit\"")
        assertThat(body).contains("\"triggerPrice\":\"85000")
        assertThat(body).contains("\"price\":\"85100")
        assertThat(body).contains("\"triggerDirection\":1")
    }

    @Test
    fun `IfTouched BUY MARKET translates with opposite trigger direction`() {
        val req =
            OrderRequest.IfTouched(
                id = "c5",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                triggerPrice = Money.of("75000"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Market\"")
        assertThat(body).contains("\"triggerPrice\":\"75000")
        assertThat(body).contains("\"triggerDirection\":2")
    }

    @Test
    fun `cancel body contains orderLinkId and category`() {
        val body = BybitOrderTranslator.toCancelBody(symbol = "BYBIT_SPOT:BTCUSDT", orderLinkId = "c1")
        assertThat(body).contains("\"category\":\"spot\"")
        assertThat(body).contains("\"symbol\":\"BTCUSDT\"")
        assertThat(body).contains("\"orderLinkId\":\"c1\"")
    }

    @Test
    fun `DAY tif maps to GTC on spot`() {
        val req =
            OrderRequest.Limit(
                id = "c6",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.DAY,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"timeInForce\":\"GTC\"")
    }

    @Test
    fun `IOC and FOK pass through directly`() {
        val req1 =
            OrderRequest.Limit(
                id = "c7",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.IOC,
                timestamp = 0L,
            )
        assertThat(BybitOrderTranslator.toCreateBody(req1)).contains("\"timeInForce\":\"IOC\"")

        val req2 = req1.copy(id = "c8", timeInForce = TimeInForce.FOK)
        assertThat(BybitOrderTranslator.toCreateBody(req2)).contains("\"timeInForce\":\"FOK\"")
    }

    @Test
    fun `parseOpenOrder extracts orderLinkId orderId symbol side and status`() {
        val json =
            Json
                .parseToJsonElement(
                    """{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","orderStatus":"New","category":"spot"}""",
                ).jsonObject
        val parsed = BybitOrderTranslator.parseOpenOrder(json)
        assertThat(parsed.clientOrderId).isEqualTo("c1")
        assertThat(parsed.brokerOrderId).isEqualTo("abc-123")
        assertThat(parsed.bareSymbol).isEqualTo("BTCUSDT")
        assertThat(parsed.side).isEqualTo(Side.BUY)
        assertThat(parsed.status).isEqualTo("New")
    }

    @Test
    fun `parseExecution extracts execId price qty and orderLinkId`() {
        val json =
            Json
                .parseToJsonElement(
                    """{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","execPrice":"79998.5","execQty":"0.01","execId":"exec-99","category":"spot"}""",
                ).jsonObject
        val parsed = BybitOrderTranslator.parseExecution(json)
        assertThat(parsed.execId).isEqualTo("exec-99")
        assertThat(parsed.clientOrderId).isEqualTo("c1")
        assertThat(parsed.brokerOrderId).isEqualTo("abc-123")
        assertThat(parsed.bareSymbol).isEqualTo("BTCUSDT")
        assertThat(parsed.side).isEqualTo(Side.BUY)
        assertThat(parsed.price).isEqualByComparingTo(Money.of("79998.5"))
        assertThat(parsed.quantity).isEqualByComparingTo(Money.of("0.01"))
    }
}
