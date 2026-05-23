package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorOcoLegsTest {
    private fun leg(
        id: String,
        side: Side,
        sibling: String,
    ) = PersistedOcoLeg(
        clientOrderId = id,
        brokerOrderId = "ticket-$id",
        strategyId = "alpha",
        request =
            OrderRequest.Stop(
                id = id,
                symbol = "XAUUSD",
                side = side,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        siblingIds = listOf(sibling),
    )

    @Test
    fun `oco legs round-trip through the file persistor`(
        @TempDir tmp: Path,
    ) {
        val p = FileStatePersistor(tmp)
        val legs = listOf(leg("oco1-a", Side.BUY, "oco1-b"), leg("oco1-b", Side.SELL, "oco1-a"))

        p.saveOcoLegs("alpha", legs)

        assertThat(p.loadOcoLegs("alpha")).isEqualTo(legs)
    }

    @Test
    fun `loadOcoLegs returns empty when nothing was saved`(
        @TempDir tmp: Path,
    ) {
        assertThat(FileStatePersistor(tmp).loadOcoLegs("never-saved")).isEmpty()
    }
}
