package com.qkt.marketdata.store.deribit

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitWireSerializationTest {
    private val jsons =
        listOf(
            Json,
            Json { encodeDefaults = true },
            Json {
                encodeDefaults = true
                explicitNulls = false
            },
            Json {
                explicitNulls = false
                ignoreUnknownKeys = true
            },
        )

    private val fullInstrument =
        DeribitInstrument(
            name = "BTC-27DEC24-90000-P",
            type = "option",
            settlementCurrency = "BTC",
            counterCurrency = "USD",
            strike = JsonPrimitive(90000),
            optionType = "put",
            expiryMs = 1735286400000L,
            contractSize = JsonPrimitive(1),
            tickSize = JsonPrimitive(0.0001),
            tickSizeSteps = listOf(DeribitTickStep(JsonPrimitive(0.005), JsonPrimitive(0.0005))),
            minTradeAmount = JsonPrimitive(0.1),
            priceIndex = "btc_usd",
        )

    private inline fun <reified T> roundTrip(value: T) {
        for (j in jsons) {
            assertThat(j.decodeFromString<T>(j.encodeToString(value))).isEqualTo(value)
        }
    }

    @Test
    fun `an instrument round trips with every field set and with none`() {
        roundTrip(fullInstrument)
        roundTrip(DeribitInstrument(name = "BTC-PERPETUAL"))
    }

    @Test
    fun `an instrument without a name is refused naming the field`() {
        assertThatThrownBy { Json.decodeFromString<DeribitInstrument>("""{"instrument_type":"option"}""") }
            .isInstanceOf(SerializationException::class.java)
            .hasMessageContaining("instrument_name")
    }

    @Test
    fun `a wrongly typed expiry is refused`() {
        assertThatThrownBy {
            Json.decodeFromString<DeribitInstrument>("""{"instrument_name":"x","expiration_timestamp":"soon"}""")
        }.isInstanceOf(SerializationException::class.java)
    }

    @Test
    fun `a tick step needs both its price and tick`() {
        roundTrip(DeribitTickStep(JsonPrimitive(1), JsonPrimitive(2)))
        assertThatThrownBy { Json.decodeFromString<DeribitTickStep>("""{"above_price":1}""") }
            .isInstanceOf(SerializationException::class.java)
            .hasMessageContaining("tick_size")
        assertThatThrownBy { Json.decodeFromString<DeribitTickStep>("""{"tick_size":1}""") }
            .isInstanceOf(SerializationException::class.java)
            .hasMessageContaining("above_price")
    }

    @Test
    fun `errors round trip with and without a reason`() {
        roundTrip(DeribitError(code = 10001, message = "Invalid params", data = DeribitErrorData("bad")))
        roundTrip(DeribitError())
        roundTrip(DeribitErrorData())
        roundTrip(DeribitErrorData("why"))
        assertThat(DeribitError(message = "m", data = DeribitErrorData()).description).isEqualTo("m")
        assertThat(DeribitError(message = "m").description).isEqualTo("m")
    }

    @Test
    fun `an error body decodes with defaults when fields are absent`() {
        val e = Json.decodeFromString<DeribitError>("{}")
        assertThat(e).isEqualTo(DeribitError(0, "", null))
        val withData = Json.decodeFromString<DeribitError>("""{"code":5,"message":"x","data":{"reason":"r"}}""")
        assertThat(withData.description).isEqualTo("x (r)")
    }

    @Test
    fun `an envelope carries a result, an error, or neither`() {
        val ser = DeribitEnvelope.serializer(String.serializer())
        for (j in jsons) {
            for (
            env in
            listOf(
                DeribitEnvelope("ok"),
                DeribitEnvelope<String>(error = DeribitError(1, "bad")),
                DeribitEnvelope<String>(),
                DeribitEnvelope("ok", DeribitError(2, "both")),
            )
            ) {
                assertThat(j.decodeFromString(ser, j.encodeToString(ser, env))).isEqualTo(env)
            }
        }
        assertThat(Json.decodeFromString(ser, "{}")).isEqualTo(DeribitEnvelope<String>())
        assertThatThrownBy { Json.decodeFromString(ser, """{"result":5}""") }
            .isInstanceOf(SerializationException::class.java)
    }

    @Test
    fun `a delivery page round trips full and empty`() {
        val d = DeribitDelivery("2024-12-27", JsonPrimitive(95000.5))
        roundTrip(DeribitDeliveryPage(listOf(d), 1))
        roundTrip(DeribitDeliveryPage())
        assertThat(Json.decodeFromString<DeribitDeliveryPage>("{}")).isEqualTo(DeribitDeliveryPage(emptyList(), 0))
        roundTrip(d)
    }

    @Test
    fun `a delivery needs its date and price`() {
        assertThatThrownBy { Json.decodeFromString<DeribitDelivery>("""{"delivery_price":1}""") }
            .isInstanceOf(SerializationException::class.java)
            .hasMessageContaining("date")
        assertThatThrownBy { Json.decodeFromString<DeribitDelivery>("""{"date":"d"}""") }
            .isInstanceOf(SerializationException::class.java)
            .hasMessageContaining("delivery_price")
    }
}
