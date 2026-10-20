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
}
