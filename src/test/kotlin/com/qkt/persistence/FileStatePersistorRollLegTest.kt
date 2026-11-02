package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.persistence.StreamLaneFixture.STREAM
import com.qkt.persistence.StreamLaneFixture.laneInFlight
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A roll's legs on disk: a live roll's collared limit legs, and the market legs a previous version saved. */
class FileStatePersistorRollLegTest {
    @Test
    fun `a lane file saved by the previous version, its legs market orders, still loads`(
        @TempDir tmp: Path,
    ) {
        fun leg(kind: String) =
            """{"type":"Market","id":"roll:9:abc:$kind","symbol":"CME:ESZ26","side":"BUY","quantity":"2",""" +
                """"timeInForce":"GTC","timestamp":1000,"strategyId":"trend","closesTicket":null,""" +
                """"closesLegId":null,"partialClose":false}"""
        val saved =
            """{"version":1,"stream":"$STREAM","contract":"CME:ESH27","strategies":[],"orders":[],"holdings":[],""" +
                """"legs":[{"leg":${leg("close")},"slices":[]},{"leg":${leg("unwind")},"slices":[]}],""" +
                """"cancelling":[],"roll":{"from":"CME:ESZ26","to":"CME:ESH27","atMs":9,"fromPrice":"1",""" +
                """"toPrice":"2","stopped":"stopped","resting":[],"holders":[],"steps":[{"kind":"closing",""" +
                """"strategyId":"trend","quantity":"-2","leg":${leg("close")}}]}}"""
        FileStatePersistor(tmp).saveStreamLane("trend", laneInFlight())
        val file =
            Files
                .walk(tmp)
                .filter { it.fileName.toString().endsWith("lane.json") }
                .findFirst()
                .get()
        Files.writeString(file, saved)

        val lane = requireNotNull(FileStatePersistor(tmp).loadStreamLane("trend", STREAM))

        assertThat(lane.legs.map { it.leg }).allMatch { it is OrderRequest.Market }
        val step = lane.roll?.steps?.single() as PersistedCarryStep.Closing
        assertThat(step.leg).isInstanceOf(OrderRequest.Market::class.java)
    }

    @Test
    fun `a collared limit leg round-trips, in the legs and in a step`(
        @TempDir tmp: Path,
    ) {
        val leg =
            OrderRequest.Limit(
                "roll:9:abc:close",
                "CME:ESZ26",
                Side.BUY,
                BigDecimal("2"),
                BigDecimal("6250.25"),
                TimeInForce.IOC,
                1_000L,
                "trend",
            )
        val base = laneInFlight()
        val lane =
            base.copy(
                legs = listOf(PersistedRollLeg(leg, emptyList())),
                roll =
                    base.roll?.copy(
                        steps = listOf(PersistedCarryStep.Closing("trend", BigDecimal("-2"), leg)),
                    ),
            )
        FileStatePersistor(tmp).saveStreamLane("trend", lane)

        assertThat(FileStatePersistor(tmp).loadStreamLane("trend", STREAM)).isEqualTo(lane)
    }
}
