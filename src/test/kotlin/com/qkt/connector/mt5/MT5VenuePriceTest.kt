package com.qkt.connector.mt5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A gateway price is a Python float: its noise past 15 significant digits is not the venue's price. */
class MT5VenuePriceTest {
    @Test
    fun `an order reply's float noise is not booked as the fill price`() {
        val reply =
            MT5OrderResponses(Json).parseOrderResponse(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":4127.5830000000005,"comment":""}}""",
            )

        assertThat(reply.result.price.toPlainString()).isEqualTo("4127.583")
    }

    @Test
    fun `a deal's float noise is not booked either, and a clean price is untouched`() {
        val parser = MT5SnapshotParser(Json { ignoreUnknownKeys = true }, MT5VenueTime(MT5ServerTimeZone.UTC))

        fun deal(price: String) =
            parser.parseDeal(Json.parseToJsonElement("""{"ticket":1,"price":$price}""").jsonObject)

        assertThat(deal("4127.6140000000005").price.toPlainString()).isEqualTo("4127.614")
        assertThat(deal("1.08431").price.toPlainString()).isEqualTo("1.08431")
        assertThat(deal("67000").price.toPlainString()).isEqualTo("67000")
    }

    @Test
    fun `a position's prices carry no float noise`() {
        // Live on the Exness demo, 2026-10-20: qkt bot positions showed "entry":82638.31999999999.
        val parser = MT5SnapshotParser(Json { ignoreUnknownKeys = true }, MT5VenueTime(MT5ServerTimeZone.UTC))
        val p =
            parser.parsePosition(
                Json
                    .parseToJsonElement(
                        """{"ticket":3387482304,"symbol":"BTCUSDm","type":0,"volume":0.01,""" +
                            """"price_open":82638.31999999999,"sl":81811.94000000001,"tp":84291.08,"profit":0.26,""" +
                            """"magic":994777,"price_current":82663.87000000001}""",
                    ).jsonObject,
            )

        assertThat(p.priceOpen.toPlainString()).isEqualTo("82638.32")
        assertThat(p.sl.toPlainString()).isEqualTo("81811.94")
        assertThat(p.tp.toPlainString()).isEqualTo("84291.08")
        assertThat(p.priceCurrent?.toPlainString()).isEqualTo("82663.87")
    }
}
