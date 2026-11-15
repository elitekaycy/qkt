package com.qkt.connector.gateway

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An event listing missing a term qkt needs, or whose terms disagree, is refused by its code, never guessed. */
class GatewayEventRefusalTest {
    private val valid =
        WireInstrument(
            "KXINXU-26NOV06H1500-B7412",
            "event",
            "USD",
            "1",
            "0.01",
            "1",
            "1",
            expiry = 1_794_006_000_000L,
            series = "KXINXU",
            event = "KXINXU-26NOV06H1500",
            strikeType = "between",
            floor = "7400",
            cap = "7425",
            openAt = 1_794_002_400_000L,
            closeAt = 1_794_006_000_000L,
        )

    private fun refused(
        wire: WireInstrument,
        reason: String,
    ) {
        assertThatThrownBy { wire.instrumentSpec("KALSHI:KXINXU_26NOV06H1500_B7412") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'KXINXU-26NOV06H1500-B7412'")
            .hasMessageContaining(reason)
    }

    @Test
    fun `a complete range contract is read`() {
        assertThat(valid.instrumentSpec("KALSHI:KXINXU_26NOV06H1500_B7412")).isNotNull
    }

    @Test
    fun `each required term is named when it is missing`() {
        refused(valid.copy(series = null), "series")
        refused(valid.copy(event = null), "event")
        refused(valid.copy(strikeType = null), "strike_type")
        refused(valid.copy(openAt = null), "open_at")
        refused(valid.copy(closeAt = null), "close_at")
        refused(valid.copy(expiry = null), "expiry")
    }

    @Test
    fun `terms that disagree are refused`() {
        refused(valid.copy(cap = null), "a floor and a cap")
        refused(valid.copy(floor = "7425", cap = "7400"), "not be above cap")
        refused(valid.copy(closeAt = 1_794_002_399_999L), "closeAtMs")
        refused(valid.copy(outcome = "2"), "outcome")
        refused(valid.copy(contractSize = "100"), "contract_size")
        refused(valid.copy(floor = "seven thousand"), "malformed")
    }

    @Test
    fun `a strike type, fee model or trading mode VGP does not define is refused by name`() {
        refused(valid.copy(strikeType = "above"), "strike_type 'above'")
        refused(valid.copy(feeModel = "flat", takerFeeRate = "0.01", feeRounding = "0.01"), "fee_model 'flat'")
        refused(valid.copy(trading = "sometimes"), "'sometimes'")
    }

    @Test
    fun `a probability fee needs its taker rate and rounding unit`() {
        refused(valid.copy(feeModel = "probability", feeRounding = "0.01"), "taker_fee_rate")
        refused(valid.copy(feeModel = "probability", takerFeeRate = "0.07"), "fee_rounding")
        refused(valid.copy(feeModel = "probability", takerFeeRate = "1", feeRounding = "0.01"), "takerRate")
    }

    @Test
    fun `a non-event instrument is read as before, whatever event fields it carries`() {
        val spot = WireInstrument("BTC_USDC", "spot", "USDC", "1", "0.5", "0.0001", "0.0001", series = "ignored")
        val future = valid.copy(code = "BTC_USDC-25DEC26", kind = "future")

        assertThat(requireNotNull(spot.instrumentSpec("DERIBIT:BTC_USDC")).meta.derivative).isNull()
        assertThat(future.instrumentSpec("DERIBIT:BTC_USDC_25DEC26")).isNull()
    }
}
