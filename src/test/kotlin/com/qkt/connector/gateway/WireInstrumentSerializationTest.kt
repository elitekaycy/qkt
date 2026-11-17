package com.qkt.connector.gateway

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import org.junit.jupiter.api.Test

class WireInstrumentSerializationTest {
    private val bare = WireInstrument("BTC", "perpetual", "USD", "1", "0.5", "0.1", "0.1")
    private val body =
        """{"code":"BTC","kind":"perpetual","currency":"USD","contract_size":"1","tick_size":"0.5",
        |"volume_step":"0.1","volume_min":"0.1"}
        """.trimMargin()
    private val optional =
        listOf(
            "expiry",
            "strike",
            "right",
            "underlying",
            "category",
            "trading",
            "series",
            "event",
            "strike_type",
            "floor",
            "cap",
            "open_at",
            "close_at",
            "occurs_at",
            "occasion",
            "can_close_early",
            "exclusive",
            "frequency",
            "title",
            "outcome",
            "fee_model",
            "taker_fee_rate",
            "maker_fee_rate",
            "fee_rounding",
        )

    @Test
    fun `an instrument with no optional terms round trips and decodes to nulls`() {
        roundTrips(bare)
        decodes(body, bare)
    }

    @Test
    fun `an instrument with every optional term round trips`() {
        val full =
            bare.copy(
                expiry = 1L,
                strike = "9",
                right = "call",
                underlying = "BTC",
                category = "crypto",
                trading = "close_only",
                series = "S",
                event = "E",
                strikeType = "between",
                floor = "1",
                cap = "2",
                openAt = 3L,
                closeAt = 4L,
                occursAt = 5L,
                occasion = "o",
                canCloseEarly = true,
                exclusive = false,
                frequency = "daily",
                title = "t",
                outcome = "1",
                feeModel = "probability",
                takerFeeRate = "0.07",
                makerFeeRate = "0.01",
                feeRounding = "0.01",
            )
        roundTrips(full)
        roundTrips(full.copy(canCloseEarly = false, exclusive = true))
    }

    @Test
    fun `a missing required term is refused by name`() {
        refusesWithout<WireInstrument>(
            body,
            "code",
            "kind",
            "currency",
            "contract_size",
            "tick_size",
            "volume_step",
            "volume_min",
        )
    }

    @Test
    fun `a wrongly typed term is refused`() {
        refusesWrongType<WireInstrument>(body, "code", "tick_size")
        refusesWrongType<WireInstrument>(body, *optional.toTypedArray())
    }
}
