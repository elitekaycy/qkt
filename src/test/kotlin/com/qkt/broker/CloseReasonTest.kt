package com.qkt.broker

import com.qkt.connector.mt5.MT5VenueDescription
import com.qkt.execution.ExitReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** One close-reason enum for every connector: MT5 deal reasons and qkt's own exits map onto it. */
class CloseReasonTest {
    @Test
    fun `a venue-stated exit reason wins over the order id`() {
        assertThat(CloseReason.of(ExitReason.STOP, "dsl-a-1")).isEqualTo(CloseReason.STOP)
        assertThat(CloseReason.of(ExitReason.TAKE_PROFIT, "dsl-a-1")).isEqualTo(CloseReason.TAKE_PROFIT)
        assertThat(CloseReason.of(ExitReason.LIQUIDATION, "dsl-a-1")).isEqualTo(CloseReason.LIQUIDATION)
        assertThat(CloseReason.of(ExitReason.EXPIRY, null)).isEqualTo(CloseReason.EXPIRY)
        assertThat(CloseReason.of(ExitReason.ROLL_FAILED, null)).isEqualTo(CloseReason.EXPIRY)
    }

    @Test
    fun `qkt's exit order ids name engine-held stops, targets, timed holds and bot closes`() {
        assertThat(CloseReason.of(null, "dsl-a-3-sl")).isEqualTo(CloseReason.STOP)
        assertThat(CloseReason.of(null, "dsl-a-3-tp")).isEqualTo(CloseReason.TAKE_PROFIT)
        assertThat(CloseReason.of(null, "dsl-a-3-close")).isEqualTo(CloseReason.TIMED)
        assertThat(CloseReason.of(ExitReason.CLOSE, "dsl-a-3-close-r2")).isEqualTo(CloseReason.TIMED)
        assertThat(CloseReason.of(null, "bot-ops-1790000000000-0a1b")).isEqualTo(CloseReason.MANUAL)
    }

    @Test
    fun `any other close is the strategy's signal`() {
        assertThat(CloseReason.of(ExitReason.CLOSE, "dsl-a-7")).isEqualTo(CloseReason.SIGNAL)
        assertThat(CloseReason.of(null, null)).isEqualTo(CloseReason.SIGNAL)
    }

    @Test
    fun `MT5 deal reasons map to the same enum`() {
        assertThat(MT5VenueDescription.closeReason(4, "[sl 4332.68]")).isEqualTo(CloseReason.STOP)
        assertThat(MT5VenueDescription.closeReason(5, "[tp 4332.68]")).isEqualTo(CloseReason.TAKE_PROFIT)
        assertThat(MT5VenueDescription.closeReason(6, "[so 12.5%]")).isEqualTo(CloseReason.STOP_OUT)
        assertThat(MT5VenueDescription.closeReason(0, null)).isEqualTo(CloseReason.MANUAL)
        assertThat(MT5VenueDescription.closeReason(2, null)).isEqualTo(CloseReason.MANUAL)
        assertThat(MT5VenueDescription.closeReason(3, "dsl-a-9-close")).isEqualTo(CloseReason.TIMED)
        assertThat(MT5VenueDescription.closeReason(3, "bot-ops-1790000000000-0a1b")).isEqualTo(CloseReason.MANUAL)
        assertThat(MT5VenueDescription.closeReason(3, "dsl-a")).isEqualTo(CloseReason.SIGNAL)
        assertThat(MT5VenueDescription.closeReason(7, null)).isNull()
    }

    @Test
    fun `an MT5 account identity names the venue by its server and the account by an opaque id`() {
        val identity = MT5VenueDescription.identity("exness", "436804390", "Exness-MT5Trial9")
        val id = VenueAccountId.mt5("436804390", "Exness-MT5Trial9")
        assertThat(identity).isEqualTo(VenueIdentity("mt5", "exness", "EXNESS", id))
        assertThat(MT5VenueDescription.identity("exness", null, null).accountId).isNull()
    }
}
