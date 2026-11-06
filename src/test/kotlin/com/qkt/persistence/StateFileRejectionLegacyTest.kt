package com.qkt.persistence

import com.qkt.broker.BookedDeals
import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.persistence.orderrequest.encodeBracket
import com.qkt.persistence.orderrequest.encodeScaleOut
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateFileRejectionLegacyTest {
    private fun Path.bumpVersion() = writeText(readText().replace(Regex("\"version\":\\s*\\d+"), "\"version\":-1"))

    private val timeExit =
        OrderRequest.TimeExit(
            id = "t",
            symbol = "XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.1"),
            target =
                OrderRequest.Market("m", "XAUUSD", Side.SELL, BigDecimal("0.1"), TimeInForce.GTC, 1L),
            deadline = Instant.ofEpochMilli(5_000L),
            onExpiry = ExpiryAction.CANCEL,
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    @Test
    fun `an unparseable pending stacks file fails the load`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve("alpha")).resolve("pending-stacks.json").writeText("{")

        assertThatThrownBy { FileStatePersistor(tmp).loadPendingStacks("alpha") }
            .hasMessageContaining("loadPendingStacks parse failed for alpha")
    }

    @Test
    fun `trade history and booked deals from another schema version are rejected`(
        @TempDir tmp: Path,
    ) {
        val p = FileStatePersistor(tmp)
        p.saveTradeHistory("alpha", PersistedTradeHistory(emptyList()))
        p.saveBookedDeals("alpha", BookedDeals(emptyList(), emptyList()))
        tmp.resolve("alpha/trade-history.json").bumpVersion()
        tmp.resolve("alpha/booked-deals.json").bumpVersion()

        assertThatThrownBy { p.loadTradeHistory("alpha") }.hasMessageContaining("loadTradeHistory schema mismatch")
        assertThatThrownBy { p.loadBookedDeals("alpha") }.hasMessageContaining("loadBookedDeals schema mismatch")
    }

    @Test
    fun `a bracket or scale out wrapped around a composite order cannot be persisted`() {
        val bracket =
            OrderRequest.Bracket(
                id = "b",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                entry = timeExit,
                takeProfit = BigDecimal("4600"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("4800")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )
        val scaleOut =
            OrderRequest.ScaleOut(
                id = "s",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                basis = timeExit,
                legs = listOf(ScaleOutLeg(BigDecimal("4600"), BigDecimal("0.5"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )

        assertThatThrownBy { encodeBracket(bracket) }.hasMessageContaining("Bracket entry TimeExit cannot be persisted")
        assertThatThrownBy {
            encodeScaleOut(
                scaleOut,
            )
        }.hasMessageContaining("ScaleOut s basis TimeExit cannot be persisted")
    }

    @Test
    fun `an unknown leg intent kind is rejected by name`() {
        assertThatThrownBy {
            LegIntentDto(
                kind = "Hedge",
            ).toDomain()
        }.hasMessageContaining("Unknown LegIntent kind: Hedge")
    }
}
