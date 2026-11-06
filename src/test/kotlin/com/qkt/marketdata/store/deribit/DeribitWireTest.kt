package com.qkt.marketdata.store.deribit

import com.qkt.instrument.OptionRoot
import com.qkt.instrument.TickStep
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitWireTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root =
        OptionRoot(
            "DERIBIT:BTC",
            "BTC",
            BigDecimal.ONE,
            TickSteps(BigDecimal("0.0001"), listOf(TickStep(BigDecimal("0.005"), BigDecimal("0.0005")))),
            BigDecimal("0.1"),
            BigDecimal("0.1"),
            "btc_usd",
        )

    private fun listed(vararg steps: Pair<String, String>) =
        DeribitInstrument(
            name = "BTC-27DEC24-90000-P",
            tickSizeSteps = steps.map { (above, tick) -> step(above, tick) },
        )

    private fun step(
        above: String,
        tick: String,
    ) = DeribitTickStep(JsonPrimitive(BigDecimal(above)), JsonPrimitive(BigDecimal(tick)))

    @Test
    fun `an error describes itself with the reason when Deribit gives one`() {
        assertThat(DeribitError(message = "Invalid params", data = DeribitErrorData("bad index")).description)
            .isEqualTo("Invalid params (bad index)")
        assertThat(DeribitError(code = 10009, message = "not_enough_funds").description).isEqualTo("not_enough_funds")
        assertThat(DeribitError().description).isEmpty()
    }

    @Test
    fun `tick steps and delivery prices decode from the venue's field names`() {
        val decoded =
            json.decodeFromString(
                DeribitTickStep.serializer(),
                """{"above_price":0.005,"tick_size":0.0005}""",
            )
        val dayJson = """{"date":"2024-12-27","delivery_price":95168.7}"""
        val day = json.decodeFromString(DeribitDelivery.serializer(), dayJson)

        assertThat(decoded).isEqualTo(step("0.005", "0.0005"))
        assertThat(day).isEqualTo(DeribitDelivery("2024-12-27", JsonPrimitive(BigDecimal("95168.7"))))
    }

    @Test
    fun `a listed tick grid equal to the declared one passes`() {
        requireMatchesRoot(listed("0.0050" to "0.00050"), root)
    }

    @Test
    fun `a listed tick grid with another tick above the same price is refused`() {
        assertThatThrownBy { requireMatchesRoot(listed("0.005" to "0.001"), root) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("tickSteps")
    }

    @Test
    fun `a listed tick grid starting at another price is refused`() {
        assertThatThrownBy { requireMatchesRoot(listed("0.01" to "0.0005"), root) }
            .hasMessageContaining("tickSteps")
    }
}
