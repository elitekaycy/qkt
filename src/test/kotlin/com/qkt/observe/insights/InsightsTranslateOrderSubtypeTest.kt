package com.qkt.observe.insights

import com.qkt.events.OrderEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsTranslateOrderSubtypeTest {
    @Test
    fun `order submit payload covers every order request subtype`() {
        val requests = SingleLegOrderRequests.samples() + CompositeOrderRequests.samples()

        val byType =
            requests.associate { request ->
                request.javaClass.simpleName to
                    InsightsTranslate
                        .fromOrderSubmit(
                            OrderEvent(request, timestamp = 1L, sequenceId = request.id.hashCode().toLong()),
                        ).payload
            }

        assertThat(byType.keys)
            .containsExactlyInAnyOrder(
                "Market",
                "Limit",
                "Stop",
                "StopLimit",
                "IfTouched",
                "TrailingStop",
                "ArmedTrailingStop",
                "SteppedStop",
                "TimeTighteningStop",
                "TrailingStopLimit",
                "StandaloneOCO",
                "OTO",
                "Bracket",
                "ScaleOut",
                "TimeExit",
                "Stack",
            )
        assertThat(byType.getValue("Market")).containsEntry("closesTicket", "ticket-1")
        assertThat(byType.getValue("Market")).containsEntry("partialClose", true)
        assertThat(
            byType.getValue("Limit"),
        ).containsEntry("limitPrice", BigDecimal("2349.5")).containsEntry("expiresAt", 2L)
        assertThat(byType.getValue("Stop")).containsEntry("stopPrice", BigDecimal("2355"))
        assertThat(
            byType.getValue("StopLimit"),
        ).containsEntry("stopPrice", BigDecimal("2355")).containsEntry("limitPrice", BigDecimal("2356"))
        assertThat(
            byType.getValue("IfTouched"),
        ).containsEntry("onTrigger", "LIMIT").containsEntry("triggerPrice", BigDecimal("2360"))
        assertThat(
            byType.getValue("TrailingStop"),
        ).containsEntry("trailMode", "ABSOLUTE").containsEntry("trailAmount", BigDecimal("10"))
        assertThat(
            byType.getValue("ArmedTrailingStop"),
        ).containsEntry("entryPrice", BigDecimal("2350")).containsEntry("mfeThreshold", BigDecimal("12"))
        assertThat(byType.getValue("SteppedStop"))
            .containsEntry("initialDistance", BigDecimal("10"))
        assertThat(byType.getValue("SteppedStop")["steps"]).isInstanceOf(List::class.java)
        assertThat(byType.getValue("TimeTighteningStop"))
            .containsEntry("intervalMs", 5_000L)
            .containsEntry("floorDistance", BigDecimal("4"))
        assertThat(
            byType.getValue("TrailingStopLimit"),
        ).containsEntry("trailMode", "PERCENT").containsEntry("limitOffset", BigDecimal("0.2"))
        assertThat(byType.getValue("StandaloneOCO")["leg1"]).isInstanceOf(Map::class.java)
        assertThat(byType.getValue("OTO")["children"]).isInstanceOf(List::class.java)
        assertThat(byType.getValue("Bracket")["stopLossAst"]).isInstanceOf(Map::class.java)
        assertThat(byType.getValue("ScaleOut")["legs"]).isInstanceOf(List::class.java)
        assertThat(
            byType.getValue("TimeExit"),
        ).containsEntry("deadline", 1718000060000L).containsEntry("onExpiry", "CLOSE_AT_MARKET")
        assertThat(byType.getValue("Stack")["stackLayers"]).isInstanceOf(List::class.java)
        @Suppress("UNCHECKED_CAST")
        val stackLayers = byType.getValue("Stack")["stackLayers"] as List<Map<String, Any?>>
        assertThat(stackLayers).hasSize(2)
        assertThat(stackLayers[1]["trigger"]).isInstanceOf(Map::class.java)
        assertThat(byType.getValue("Stack")["outerBracket"]).isInstanceOf(Map::class.java)
    }
}
