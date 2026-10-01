package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.persistence.StreamLaneFixture.STREAM
import com.qkt.persistence.StreamLaneFixture.laneInFlight
import com.qkt.persistence.StreamLaneFixture.market
import com.qkt.persistence.StreamLaneFixture.order
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorStreamLaneTest {
    @Test
    fun `a lane mid-roll round-trips with every step, order and fill field intact`(
        @TempDir tmp: Path,
    ) {
        val lane = laneInFlight()
        FileStatePersistor(tmp).saveStreamLane("trend", lane)

        assertThat(FileStatePersistor(tmp).loadStreamLane("trend", STREAM)).isEqualTo(lane)
    }

    @Test
    fun `a lane with no roll in flight and no contract traded yet round-trips`(
        @TempDir tmp: Path,
    ) {
        val lane =
            PersistedStreamLane(
                STREAM,
                null,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                roll = null,
            )
        FileStatePersistor(tmp).saveStreamLane("trend", lane)

        assertThat(FileStatePersistor(tmp).loadStreamLane("trend", STREAM)).isEqualTo(lane)
    }

    @Test
    fun `each stream has its own record and a later save replaces the last`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val other =
            PersistedStreamLane("CME:NQ", 1, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        persistor.saveStreamLane("trend", laneInFlight())
        persistor.saveStreamLane("trend", other)
        val rolled = laneInFlight().copy(contractIndex = 4, roll = null)
        persistor.saveStreamLane("trend", rolled)

        assertThat(persistor.loadStreamLane("trend", STREAM)).isEqualTo(rolled)
        assertThat(persistor.loadStreamLane("trend", "CME:NQ")).isEqualTo(other)
    }

    @Test
    fun `a stream never saved loads as null`(
        @TempDir tmp: Path,
    ) {
        assertThat(FileStatePersistor(tmp).loadStreamLane("trend", STREAM)).isNull()
    }

    @Test
    fun `an order the file cannot encode is recorded as a failed write and the last save stays`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val lane = laneInFlight()
        persistor.saveStreamLane("trend", lane)
        val target = market("t-1", STREAM, Side.BUY)
        val timed =
            OrderRequest.TimeExit(
                "x-1",
                STREAM,
                Side.BUY,
                BigDecimal("2"),
                target,
                Instant.ofEpochMilli(9_000L),
                ExpiryAction.CANCEL,
                TimeInForce.GTC,
                0L,
                "trend",
            )

        persistor.saveStreamLane("trend", lane.copy(orders = listOf(order(timed))))

        assertThat(persistor.failedWrites).isEqualTo(1L)
        assertThat(persistor.loadStreamLane("trend", STREAM)).isEqualTo(lane)
    }

    @Test
    fun `a record from another schema version fails loudly instead of loading`(
        @TempDir tmp: Path,
    ) {
        FileStatePersistor(tmp).saveStreamLane("trend", laneInFlight())
        val file = tmp.resolve("trend").resolve("$STREAM-lane.json")
        Files.writeString(file, Files.readString(file).replace("\"version\":1", "\"version\":99"))

        assertThatThrownBy { FileStatePersistor(tmp).loadStreamLane("trend", STREAM) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("schema mismatch")
    }

    @Test
    fun `an unreadable record fails loudly instead of loading`(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(tmp.resolve("trend"))
        Files.writeString(tmp.resolve("trend").resolve("$STREAM-lane.json"), "{not json")

        assertThatThrownBy { FileStatePersistor(tmp).loadStreamLane("trend", STREAM) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("parse failed")
    }

    @Test
    fun `the in-memory persistor keeps each stream's last save`() {
        val persistor = NoopStatePersistor()
        persistor.saveStreamLane("trend", laneInFlight())

        assertThat(persistor.loadStreamLane("trend", STREAM)).isEqualTo(laneInFlight())
        assertThat(persistor.loadStreamLane("trend", "CME:NQ")).isNull()
        assertThat(persistor.loadStreamLane("carry", STREAM)).isNull()
    }
}
