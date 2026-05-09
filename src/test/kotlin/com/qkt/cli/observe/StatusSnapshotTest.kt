package com.qkt.cli.observe

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusSnapshotTest {
    @Test
    fun `serializes BigDecimal as plain number not string`() {
        val snap =
            StatusSnapshot(
                strategy = "x",
                version = 1,
                uptimeMs = 47823,
                startedAt = "2026-05-08T14:31:14Z",
                equity = BigDecimal("9997.66"),
                balance = BigDecimal("10000.00"),
                realized = BigDecimal("-2.34"),
                unrealized = BigDecimal("0.00"),
                positions = listOf(PositionDto("BTCUSDT", BigDecimal("0.001"), BigDecimal("68234.50"))),
                lastTrade = null,
            )
        val s = Json.encodeToString(StatusSnapshot.serializer(), snap)
        assertThat(s).contains("\"equity\":9997.66")
        assertThat(s).contains("\"realized\":-2.34")
        assertThat(s).doesNotContain("E+").doesNotContain("e+")
        assertThat(s).contains("\"qty\":0.001")
        assertThat(s).contains("\"avgPrice\":68234.50")
    }

    @Test
    fun `serializes lastTrade DTO when present`() {
        val snap =
            StatusSnapshot(
                strategy = "x",
                version = 1,
                uptimeMs = 1L,
                startedAt = "2026-05-08T14:31:14Z",
                equity = BigDecimal("100.00"),
                balance = BigDecimal("100.00"),
                realized = BigDecimal("0.00"),
                unrealized = BigDecimal("0.00"),
                positions = emptyList(),
                lastTrade =
                    TradeDto(
                        timestamp = "2026-05-08T14:31:00Z",
                        side = "BUY",
                        symbol = "BTCUSDT",
                        qty = BigDecimal("0.5"),
                        price = BigDecimal("100.00"),
                        realized = BigDecimal("0.00"),
                    ),
            )
        val s = Json.encodeToString(StatusSnapshot.serializer(), snap)
        assertThat(s).contains("\"side\":\"BUY\"")
        assertThat(s).contains("\"qty\":0.5")
        assertThat(s).contains("\"price\":100.00")
    }

    @Test
    fun `null lastTrade serializes as null`() {
        val snap =
            StatusSnapshot(
                strategy = "x",
                version = 1,
                uptimeMs = 1L,
                startedAt = "2026-05-08T14:31:14Z",
                equity = BigDecimal("0"),
                balance = BigDecimal("0"),
                realized = BigDecimal("0"),
                unrealized = BigDecimal("0"),
                positions = emptyList(),
                lastTrade = null,
            )
        val s = Json.encodeToString(StatusSnapshot.serializer(), snap)
        assertThat(s).contains("\"lastTrade\":null")
    }

    @Test
    fun `pendingStackLayers appears in serialized snapshot`() {
        val snap =
            StatusSnapshot(
                strategy = "x",
                version = 1,
                uptimeMs = 1L,
                startedAt = "2026-05-08T14:31:14Z",
                equity = BigDecimal("0"),
                balance = BigDecimal("0"),
                realized = BigDecimal("0"),
                unrealized = BigDecimal("0"),
                positions = emptyList(),
                lastTrade = null,
                pendingStackLayers =
                    listOf(
                        PendingStackLayer(
                            stackId = "stk-1",
                            layer = 2,
                            triggerPrice = BigDecimal("50100"),
                            side = "BUY",
                            quantity = BigDecimal("0.20"),
                        ),
                    ),
            )
        val s = Json.encodeToString(StatusSnapshot.serializer(), snap)
        assertThat(s).contains("\"pendingStackLayers\"")
        assertThat(s).contains("\"stackId\":\"stk-1\"")
        assertThat(s).contains("\"layer\":2")
        assertThat(s).contains("\"triggerPrice\":50100")
        assertThat(s).contains("\"side\":\"BUY\"")
        assertThat(s).contains("\"quantity\":0.20")
    }

    @Test
    fun `pendingStackLayers omitted from output when empty`() {
        val snap =
            StatusSnapshot(
                strategy = "x",
                version = 1,
                uptimeMs = 1L,
                startedAt = "2026-05-08T14:31:14Z",
                equity = BigDecimal("0"),
                balance = BigDecimal("0"),
                realized = BigDecimal("0"),
                unrealized = BigDecimal("0"),
                positions = emptyList(),
                lastTrade = null,
            )
        val s = Json.encodeToString(StatusSnapshot.serializer(), snap)
        // kotlinx.serialization skips default values by default; the field is absent, not [].
        assertThat(s).doesNotContain("\"pendingStackLayers\"")
    }
}
