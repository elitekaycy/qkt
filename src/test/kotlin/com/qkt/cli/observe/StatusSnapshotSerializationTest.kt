package com.qkt.cli.observe

import com.qkt.cli.assertRoundTrips
import com.qkt.cli.shim.Test
import com.qkt.cli.shim.assertEquals
import com.qkt.cli.shim.assertFailsWith
import com.qkt.cli.shim.assertTrue
import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StatusSnapshotSerializationTest {
    private val trade =
        TradeDto(
            "2026-01-01T00:00:00Z",
            "BUY",
            "XAUUSD",
            BigDecimal("1.50"),
            BigDecimal("2000.25"),
            BigDecimal("-3.10"),
        )
    private val position = PositionDto("XAUUSD", BigDecimal("0.10"), BigDecimal("1999.50"))

    private fun minimal() =
        StatusSnapshot(
            strategy = "s",
            version = 1,
            uptimeMs = 5L,
            startedAt = "2026-01-01T00:00:00Z",
            equity = BigDecimal("100.00"),
            balance = BigDecimal("99.00"),
            realized = BigDecimal("1.00"),
            unrealized = BigDecimal("0.00"),
            positions = emptyList(),
            lastTrade = null,
        )

    @Test
    fun smallDtosRoundTrip() {
        assertRoundTrips(position)
        assertRoundTrips(trade)
        assertRoundTrips(PendingStackLayer("st", 2, BigDecimal("1.10"), "SELL", BigDecimal("0.50")))
        assertRoundTrips(StreamStopDto("s", "gold", "roll failed"))
        assertRoundTrips(PersistenceHealthDto())
        assertRoundTrips(PersistenceHealthDto(true, 10, 2, 3, 1, 4, 7, 9))
        assertRoundTrips(AuditJournalDto())
        assertRoundTrips(AuditJournalDto(true, 5, 1, 2, 3, 4))
    }

    @Test
    fun snapshotRoundTripMinimalAndFull() {
        val minimal = minimal()
        val full =
            minimal.copy(
                positions = listOf(position),
                lastTrade = trade,
                pendingStackLayers = listOf(PendingStackLayer("st", 1, BigDecimal("1.0"), "BUY", BigDecimal("2.0"))),
                streamBrokers = mapOf("gold" to "mt5"),
                inboundQueueDepth = 3,
                engineStalls = 2L,
                engineWorstHoldMs = 1500L,
                droppedTicks = 8L,
                staleSymbols = listOf("XAUUSD"),
                clockSkewedSymbols = mapOf("EURUSD" to 900L),
                persistence = PersistenceHealthDto(true, 1, 1, 1, 1, 1, 1, 1),
                auditJournal = AuditJournalDto(true, 1, 1, 1, 1, 1),
                halted = true,
                haltReason = "drawdown",
                haltScope = "PERSISTENT",
                haltPersistent = true,
                haltedAt = "2026-01-02T00:00:00Z",
                streamStops = listOf(StreamStopDto("s", "gold", "r")),
            )
        assertRoundTrips(minimal)
        assertRoundTrips(full)
    }

    @Test
    fun bigDecimalsAreWrittenAsRawNumbers() {
        val text = Json.encodeToString(position)
        assertEquals("""{"symbol":"XAUUSD","qty":0.10,"avgPrice":1999.50}""", text)
    }

    @Test
    fun snapshotDefaultsApplyWhenOptionalFieldsAreAbsent() {
        val body =
            """{"strategy":"s","version":1,"uptimeMs":1,"startedAt":"t","equity":1,"balance":1,"realized":0,"unrealized":0,"positions":[],"lastTrade":null}"""
        val snapshot = Json.decodeFromString<StatusSnapshot>(body)
        assertEquals(false, snapshot.halted)
        assertEquals(PersistenceHealthDto(), snapshot.persistence)
        assertEquals(AuditJournalDto(), snapshot.auditJournal)
        assertTrue(snapshot.streamStops.isEmpty() && snapshot.staleSymbols.isEmpty())
    }

    @Test
    fun missingRequiredFieldsAreNamed() {
        val error = assertFailsWith<SerializationException> { Json.decodeFromString<StatusSnapshot>("{}") }
        val message = error.message.orEmpty()
        for (field in listOf("strategy", "version", "equity", "positions", "lastTrade")) {
            assertTrue(field in message, message)
        }
        assertFailsWith<SerializationException> { Json.decodeFromString<TradeDto>("""{"timestamp":"t"}""") }
        assertFailsWith<SerializationException> { Json.decodeFromString<StreamStopDto>("{}") }
    }

    @Test
    fun badNumbersAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<PositionDto>("""{"symbol":"x","qty":"abc","avgPrice":1}""")
        }
    }
}
