package com.qkt.cli

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PromotionSerializationTest {
    private val paperFull =
        PaperValidationMetrics(
            days = 14,
            trades = 40,
            avgSlippageBps = 1.5,
            p95SlippageBps = 4.25,
            rejectionRatePct = 0.5,
            missedFills = 2,
            status = "pass",
        )
    private val approval =
        PromotionApproval(PromotionState.PAPER, "alice", "reviewed", "2026-01-01T00:00:00Z")
    private val waiverFull =
        PromotionWaiver(listOf("paper", "all"), "late", "bob", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")
    private val waiverBare = PromotionWaiver(emptyList(), "r", "a", "2026-01-01T00:00:00Z")

    @Test
    fun paperMetricsRoundTripFullAndEmpty() {
        assertRoundTrips(paperFull)
        assertRoundTrips(PaperValidationMetrics())
        assertEquals(PaperValidationMetrics(), Json.decodeFromString<PaperValidationMetrics>("{}"))
    }

    @Test
    fun approvalAndWaiverRoundTrip() {
        assertRoundTrips(approval)
        assertRoundTrips(waiverFull)
        assertRoundTrips(waiverBare)
        assertNull(Json.decodeFromString<PromotionWaiver>(Json.encodeToString(waiverBare)).expiresAt)
    }

    @Test
    fun recordRoundTripWithEverythingAndNothing() {
        val now = Instant.parse("2026-03-01T00:00:00Z")
        val full =
            PromotionRecord.create(
                "s",
                "h",
                PromotionState.PAPER,
                "why",
                now,
                mapOf("k" to "v"),
                paperFull,
                listOf(approval),
                listOf(waiverFull, waiverBare),
            )
        val bare = PromotionRecord.create("s", "h", PromotionState.DRAFT, "why", now)
        assertRoundTrips(full)
        assertRoundTrips(bare)
        assertTrue(bare.evidence.isEmpty() && bare.paper == null && bare.approvals.isEmpty())
    }

    @Test
    fun recordRefusesMissingRequiredFieldsByName() {
        val error = assertFailsWith<SerializationException> { Json.decodeFromString<PromotionRecord>("{}") }
        val message = error.message.orEmpty()
        for (field in listOf("id", "strategy", "strategyHash", "state", "createdAt", "updatedAt", "rationale")) {
            assertTrue(field in message, message)
        }
    }

    @Test
    fun unknownStateAndWrongTypesAreRefused() {
        val body =
            """{"id":"1","strategy":"s","strategyHash":"h","state":"nope","createdAt":"a","updatedAt":"b","rationale":"r"}"""
        assertFailsWith<SerializationException> { Json.decodeFromString<PromotionRecord>(body) }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PaperValidationMetrics>("""{"days":"many"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<PromotionApproval>("""{"state":"paper"}""")
        }
    }

    @Test
    fun updateKeepsOrReplacesEachField() {
        val now = Instant.parse("2026-03-01T00:00:00Z")
        val base = PromotionRecord.create("s", "h", PromotionState.DRAFT, "r", now, mapOf("a" to "b"), paperFull)
        val kept = base.update(now.plusSeconds(5))
        assertEquals(base.state, kept.state)
        assertEquals(base.evidence, kept.evidence)
        assertEquals(base.paper, kept.paper)
        assertTrue(base.id != kept.id)
        val changed =
            base.update(
                now,
                state = PromotionState.PAPER,
                rationale = "new",
                evidence = emptyMap(),
                paper = null,
                approvals = listOf(approval),
                waivers = listOf(waiverBare),
            )
        assertEquals("new", changed.rationale)
        assertNull(changed.paper)
        assertTrue(changed.approvedFor(PromotionState.PAPER))
        assertEquals(listOf(waiverBare), changed.waivers)
    }

    @Test
    fun approvedForNeedsStateAndReason() {
        val blank = approval.copy(reason = " ")
        val now = Instant.now()
        val rec = PromotionRecord.create("s", "h", PromotionState.DRAFT, "r", now, approvals = listOf(blank))
        assertEquals(false, rec.approvedFor(PromotionState.PAPER))
        assertEquals(false, rec.copy(approvals = emptyList()).approvedFor(PromotionState.DRAFT))
    }

    @Test
    fun gateResultRoundTripAndBlocked() {
        val full =
            PromotionGateResult(
                "s",
                "paper",
                "id",
                "hash",
                paperFull,
                "live",
                true,
                false,
                listOf("a"),
                listOf("b"),
                listOf("w"),
            )
        val bare =
            PromotionGateResult(strategy = "s", requiredState = "live", enforced = false, eligibleForProduction = false)
        assertRoundTrips(full)
        assertRoundTrips(bare)
        assertTrue(full.blocked)
        assertEquals(false, bare.blocked)
        assertEquals(false, full.copy(eligibleForProduction = true).blocked)
        val error = assertFailsWith<SerializationException> { Json.decodeFromString<PromotionGateResult>("{}") }
        assertTrue("requiredState" in error.message.orEmpty())
    }
}
