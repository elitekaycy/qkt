package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionContract
import com.qkt.instrument.OptionRight
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.Test

/**
 * qkt prices an option's Greeks from its mark IV and forward ([StructureGreeks.perUnit]); a recorded Deribit
 * ticker (`options/deribit-ticker-greeks`, see its PROVENANCE) shows the venue's published Greeks are the same
 * model on the same inputs, published to 5 decimals: so qkt computes rather than carries them.
 */
class DeribitGreeksAgreementTest {
    private fun recorded(name: String): JsonObject =
        Json
            .parseToJsonElement(javaClass.getResource("/options/deribit-ticker-greeks/$name")!!.readText())
            .jsonObject
            .getValue("result")
            .jsonObject

    private val ticker = recorded("ticker.json")
    private val venue = ticker.getValue("greeks").jsonObject
    private val contract =
        OptionContract(
            "DERIBIT:BTC_USDC_30OCT26_110000_C",
            BigDecimal("110000"),
            OptionRight.CALL,
            recorded("instrument.json").getValue("expiration_timestamp").jsonPrimitive.long,
        )
    private val ours =
        StructureGreeks.perUnit(
            contract,
            ticker.getValue("underlying_price").jsonPrimitive.double,
            ticker.getValue("mark_iv").jsonPrimitive.double,
            ticker.getValue("timestamp").jsonPrimitive.long,
        )

    private fun venue(greek: String) = venue.getValue(greek).jsonPrimitive.double

    @Test
    fun `delta, vega and theta agree with deribit's within its rounding and 0_05 percent`() {
        assertThat(ours.delta).isCloseTo(venue("delta"), Offset.offset(0.00002))
        assertThat(ours.vega).isCloseTo(venue("vega"), Percentage.withPercentage(0.05))
        assertThat(ours.theta).isCloseTo(venue("theta"), Percentage.withPercentage(0.05))
    }

    @Test
    fun `deribit's gamma is ours rounded to 5 decimals, which leaves a btc option one digit`() {
        val rounded = BigDecimal.valueOf(ours.gamma).setScale(5, RoundingMode.HALF_UP)

        assertThat(rounded).isEqualByComparingTo(BigDecimal.valueOf(venue("gamma")))
        assertThat(ours.gamma).isCloseTo(0.0000059, Offset.offset(0.0000001))
    }
}
