package com.qkt.execution

import com.qkt.execution.OrderRequestEvidenceRequests.allRequests
import com.qkt.strategy.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SmallExecutionPiecesTest {
    @Test
    fun `all ids walks both legs of a standalone oco`() {
        val oco = allRequests().single { it is OrderRequest.StandaloneOCO }

        assertThat(oco.allIds()).containsExactly("oco", "oco-limit", "oco-stop")
    }

    @Test
    fun `all ids of every request includes its own id first`() {
        for (request in allRequests()) assertThat(request.allIds().first()).isEqualTo(request.id)
        val oto = allRequests().single { it is OrderRequest.OTO }
        assertThat(oto.allIds()).containsExactly("oto", "oto-parent", "oto-child")
    }

    @Test
    fun `signals that carry no new order translate to no request`() {
        val signals =
            listOf(
                Signal.CancelPendingForSymbol("X"),
                Signal.SubmitGroup("struct", "alias", emptyList()),
                Signal.Suppressed("X", "cooldown"),
            )

        assertThat(signals.map { it.toOrderRequest(id = "o", ts = 0L) }).containsOnlyNulls()
    }
}
