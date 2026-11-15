package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Position, pending-order and deal rows with fields missing, null or unparseable. */
class MT5SnapshotParserTest {
    private val parser = MT5SnapshotParser(Json { ignoreUnknownKeys = true }, MT5VenueTime(MT5ServerTimeZone.UTC))

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun allKeys(
        keys: List<String>,
        literal: String,
    ): JsonObject = obj(keys.joinToString(",", "{", "}") { "\"$it\":$literal" })

    @Test
    fun `pending orders are read from a bare array an enveloped array or an orders object`() {
        val row = """{"ticket":5,"symbol":"XAUUSDm"}"""

        assertThat(parser.parsePendingOrders("[$row]").map { it.ticket }).containsExactly(5L)
        assertThat(parser.parsePendingOrders("""{"data":[$row]}""").map { it.ticket }).containsExactly(5L)
        assertThat(parser.parsePendingOrders("""{"orders":[$row],"total":1}""").map { it.ticket }).containsExactly(5L)
    }

    @Test
    fun `a pending-order answer with no order list is no orders`() {
        assertThat(parser.parsePendingOrders("""{"total":0}""")).isEmpty()
        assertThat(parser.parsePendingOrders("7")).isEmpty()
        assertThat(parser.parsePendingOrders("null")).isEmpty()
    }

    @Test
    fun `positions are read from a bare array or the data envelope`() {
        val row = Mt5RoutedGateway.position(9, "0.3")

        assertThat(parser.parsePositions("[$row]").single().ticket).isEqualTo(9L)
        assertThat(parser.parsePositions("""{"data":[$row]}""").single().volume).isEqualByComparingTo("0.3")
    }

    @Test
    fun `a position carries its optional fields when the venue sends them`() {
        val position =
            parser.parsePosition(
                obj(
                    """{"ticket":1,"symbol":"XAUUSDm","type":1,"volume":"0.5","price_open":"2400.5","sl":"2410","tp":"2380",
                    "profit":"-3.5","magic":7,"time_msc":1790003221276,"comment":"c","client_order_id":"cid","swap":"-0.4",
                    "price_current":"2402"}""",
                ),
            )

        assertThat(position).isEqualTo(
            MT5Position(
                ticket = 1,
                symbol = "XAUUSDm",
                type = 1,
                volume = BigDecimal("0.5"),
                priceOpen = BigDecimal("2400.5"),
                sl = BigDecimal("2410"),
                tp = BigDecimal("2380"),
                profit = BigDecimal("-3.5"),
                magic = 7,
                openTime = 1_790_003_221_276L,
                comment = "c",
                clientOrderId = "cid",
                swap = BigDecimal("-0.4"),
                priceCurrent = BigDecimal("2402"),
            ),
        )
    }

    @Test
    fun `a position's optional fields are absent when null or unparseable`() {
        val required =
            """"ticket":1,"symbol":"XAUUSDm","type":0,"volume":"0.5","price_open":"2400","sl":"0","tp":"0","profit":"0","magic":7"""
        val nulls =
            parser.parsePosition(
                obj(
                    """{$required,"time_msc":null,"comment":null,"client_order_id":null,"swap":null,"price_current":null}""",
                ),
            )
        val junk = parser.parsePosition(obj("""{$required,"time_msc":"x","swap":"x","price_current":"x"}"""))

        assertThat(nulls).isEqualTo(parser.parsePosition(obj("{$required}"))).isEqualTo(junk)
        assertThat(nulls.openTime).isZero()
        assertThat(listOf(nulls.comment, nulls.clientOrderId, nulls.swap, nulls.priceCurrent)).containsOnlyNulls()
    }

    @Test
    fun `a pending order with every field missing null or unparseable is a blank row`() {
        val keys =
            listOf(
                "ticket",
                "symbol",
                "type",
                "type_str",
                "volume",
                "volume_current",
                "volume_initial",
                "price_open",
                "sl",
                "tp",
                "magic",
                "time_setup",
                "time_expiration",
                "comment",
                "client_order_id",
            )
        val blank =
            MT5PendingOrder(
                ticket = 0,
                symbol = "",
                type = "",
                volume = BigDecimal.ZERO,
                priceOpen = BigDecimal.ZERO,
                sl = BigDecimal.ZERO,
                tp = BigDecimal.ZERO,
                magic = 0,
                timeSetup = 0,
                timeExpiration = 0,
            )

        assertThat(parser.parsePendingOrder(obj("{}"))).isEqualTo(blank)
        assertThat(parser.parsePendingOrder(allKeys(keys, "null"))).isEqualTo(blank)
        val junk = parser.parsePendingOrder(allKeys(keys, "\"x\""))
        assertThat(junk).isEqualTo(blank.copy(symbol = "x", type = "x", comment = "x", clientOrderId = "x"))
    }

    @Test
    fun `a pending order takes its numeric type and its first reported volume`() {
        val numeric = parser.parsePendingOrder(obj("""{"type":3,"volume_initial":"0.4","time_setup":1790003221}"""))
        val named =
            parser.parsePendingOrder(
                obj("""{"type":3,"type_str":"SELL_LIMIT","volume":"0.2","volume_current":"0.1"}"""),
            )

        assertThat(numeric.type).isEqualTo("3")
        assertThat(numeric.volume).isEqualByComparingTo("0.4")
        assertThat(numeric.timeSetup).isEqualTo(1_790_003_221L)
        assertThat(named.type).isEqualTo("SELL_LIMIT")
        assertThat(named.volume).isEqualByComparingTo("0.2")
    }

    @Test
    fun `a deal with every field missing null or unparseable is a blank row`() {
        val keys =
            listOf(
                "ticket",
                "order",
                "position_id",
                "symbol",
                "type",
                "entry",
                "volume",
                "price",
                "profit",
                "commission",
                "swap",
                "fee",
                "magic",
                "comment",
                "time_msc",
                "client_order_id",
                "reason",
            )
        val zero = BigDecimal.ZERO
        val blank = MT5Deal(0, 0, 0, "", 0, 0, zero, zero, zero, zero, zero, zero, 0, null, 0)

        assertThat(parser.parseDeal(obj("{}"))).isEqualTo(blank)
        assertThat(parser.parseDeal(allKeys(keys, "null"))).isEqualTo(blank)
        assertThat(parser.parseDeal(allKeys(keys, "\"x\"")))
            .isEqualTo(blank.copy(symbol = "x", comment = "x", clientOrderId = "x"))
    }

    @Test
    fun `a deal carries every field the venue reports`() {
        val deal =
            parser.parseDeal(
                obj(
                    """{"ticket":1,"order":2,"position_id":3,"symbol":"XAUUSDm","type":1,"entry":1,"volume":"0.5",
                    "price":"2400.5","profit":"12.5","commission":"-0.7","swap":"-0.1","fee":"-0.2","magic":7,"comment":"sl",
                    "time_msc":1790003221276,"client_order_id":"cid","reason":4}""",
                ),
            )

        assertThat(deal).isEqualTo(
            MT5Deal(
                ticket = 1,
                orderTicket = 2,
                positionTicket = 3,
                symbol = "XAUUSDm",
                type = 1,
                entry = 1,
                volume = BigDecimal("0.5"),
                price = BigDecimal("2400.5"),
                profit = BigDecimal("12.5"),
                commission = BigDecimal("-0.7"),
                swap = BigDecimal("-0.1"),
                fee = BigDecimal("-0.2"),
                magic = 7,
                comment = "sl",
                timeMs = 1_790_003_221_276L,
                clientOrderId = "cid",
                reason = 4,
            ),
        )
    }
}
