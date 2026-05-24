package com.qkt.broker.mt5

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5OrderTranslatorGtdTest {
    private val profile =
        MT5DefaultProfiles.exness.copy(
            instrumentOverrides =
                mapOf(
                    "EXNESS:EURUSD" to
                        InstrumentSpec(
                            minVolume = BigDecimal("0.01"),
                            volumeStep = BigDecimal("0.01"),
                            pointSize = BigDecimal("0.00001"),
                            digits = 5,
                            tradeStopsLevelPoints = 0,
                        ),
                ),
        )
    private val translator = MT5OrderTranslator(profile, MT5Symbol(profile.symbolPolicy))

    private fun limit(expiresAt: Long?): OrderRequest.Limit =
        OrderRequest.Limit(
            id = "ord-1",
            symbol = "EXNESS:EURUSD",
            side = Side.BUY,
            quantity = Money.of("0.10"),
            limitPrice = Money.of("1.10"),
            timeInForce = if (expiresAt != null) TimeInForce.GTD else TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "s1",
            expiresAt = expiresAt,
        )

    @Test
    fun `expiresAt populates wire expiration in seconds`() {
        val single = translator.translate(limit(1_700_001_800_000L)) as MT5Translation.Single
        assertThat(single.request.expiration).isEqualTo(1_700_001_800L)
    }

    @Test
    fun `null expiresAt leaves wire expiration null`() {
        val single = translator.translate(limit(null)) as MT5Translation.Single
        assertThat(single.request.expiration).isNull()
    }
}
