package com.qkt.persistence

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorGtdTest {
    @Test
    fun `Limit with expiresAt round-trips through the persistor`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val req =
            OrderRequest.Limit(
                id = "l1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTD,
                timestamp = 100L,
                strategyId = "s1",
                expiresAt = 1_700_001_800_000L,
            )
        persistor.savePendingOrders("s1", mapOf("l1" to req))
        val loaded = persistor.loadPendingOrders("s1")
        val back = loaded["l1"] as OrderRequest.Limit
        assertThat(back.expiresAt).isEqualTo(1_700_001_800_000L)
        assertThat(back.timeInForce).isEqualTo(TimeInForce.GTD)
    }

    @Test
    fun `Limit with null expiresAt round-trips and stays null`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val req =
            OrderRequest.Limit(
                id = "l2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
                strategyId = "s1",
            )
        persistor.savePendingOrders("s1", mapOf("l2" to req))
        val back = persistor.loadPendingOrders("s1")["l2"] as OrderRequest.Limit
        assertThat(back.expiresAt).isNull()
    }
}
