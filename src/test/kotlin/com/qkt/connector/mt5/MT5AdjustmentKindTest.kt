package com.qkt.connector.mt5

import com.qkt.broker.AdjustmentKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Every MT5 non-trade deal type maps to its adjustment kind. */
class MT5AdjustmentKindTest {
    private fun kind(
        type: Int,
        comment: String? = null,
        hasSymbol: Boolean = false,
    ) = MT5Adjustments.kindOf(type, comment, hasSymbol)

    @Test
    fun `a balance deal is a dividend only when it names a symbol or says so`() {
        assertThat(kind(2)).isEqualTo(AdjustmentKind.BALANCE)
        assertThat(kind(2, "Deposit")).isEqualTo(AdjustmentKind.BALANCE)
        assertThat(kind(2, "AAPL Dividend")).isEqualTo(AdjustmentKind.DIVIDEND)
        assertThat(kind(2, null, hasSymbol = true)).isEqualTo(AdjustmentKind.DIVIDEND)
    }

    @Test
    fun `each other deal type has its own kind and an unlisted one is unknown`() {
        val kinds = (3..18).associateWith { kind(it) }

        assertThat(kinds).containsAllEntriesOf(
            mapOf(
                3 to AdjustmentKind.CREDIT,
                4 to AdjustmentKind.CHARGE,
                5 to AdjustmentKind.CORRECTION,
                6 to AdjustmentKind.BONUS,
                7 to AdjustmentKind.COMMISSION,
                11 to AdjustmentKind.COMMISSION,
                12 to AdjustmentKind.INTEREST,
                13 to AdjustmentKind.CORRECTION,
                14 to AdjustmentKind.CORRECTION,
                15 to AdjustmentKind.DIVIDEND,
                16 to AdjustmentKind.DIVIDEND,
                17 to AdjustmentKind.TAX,
                18 to AdjustmentKind.UNKNOWN,
            ),
        )
        assertThat(kind(-1)).isEqualTo(AdjustmentKind.UNKNOWN)
    }
}
