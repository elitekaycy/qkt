package com.qkt.cli

import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The promotion lifecycle's ordering, waivers and gate configuration parsing. */
class PromotionModelTest {
    private val now = Instant.parse("2026-11-06T00:00:00Z")

    private fun waiver(
        vararg gates: String,
        expiresAt: String? = null,
    ) = PromotionWaiver(gates.toList(), "reason", "ops", "2026-11-01T00:00:00Z", expiresAt)

    @Test
    fun `states rank from draft up to production and retired satisfies nothing but itself`() {
        val ladder =
            listOf(
                PromotionState.DRAFT,
                PromotionState.RESEARCH,
                PromotionState.CANDIDATE,
                PromotionState.PAPER,
                PromotionState.SHADOW_LIVE,
                PromotionState.SMALL_CAPITAL,
                PromotionState.PRODUCTION,
            )
        ladder.forEachIndexed { i, state ->
            assertThat(ladder.filter { state.atLeast(it) }).isEqualTo(ladder.take(i + 1))
            assertThat(PromotionState.RETIRED.atLeast(state)).isFalse()
            assertThat(state.atLeast(PromotionState.RETIRED)).isTrue()
        }
        assertThat(PromotionState.RETIRED.atLeast(PromotionState.RETIRED)).isTrue()
    }

    @Test
    fun `a state id is read case-insensitively with underscores or hyphens`() {
        assertThat(PromotionState.fromId(" Shadow_Live ")).isEqualTo(PromotionState.SHADOW_LIVE)
        assertThat(PromotionState.fromId("small-capital")).isEqualTo(PromotionState.SMALL_CAPITAL)
        assertThat(PromotionState.fromId("live")).isNull()
        assertThat(PromotionState.fromId(null)).isNull()
    }

    @Test
    fun `a waiver is active until its expiry and an unreadable expiry is treated as expired`() {
        assertThat(waiver("paper").active(now)).isTrue()
        assertThat(waiver("paper", expiresAt = "2026-11-07T00:00:00Z").active(now)).isTrue()
        assertThat(waiver("paper", expiresAt = "2026-11-06T00:00:00Z").active(now)).isFalse()
        assertThat(waiver("paper", expiresAt = "next week").active(now)).isFalse()
    }

    @Test
    fun `a waiver covers its named gates, a gate family and everything when it says all`() {
        val named = waiver(" Paper_Days ", "", "approval")
        assertThat(named.waives("paper_days")).isTrue()
        assertThat(named.waives("APPROVAL:production")).isTrue()
        assertThat(named.waives("walk_forward")).isFalse()
        assertThat(waiver("ALL").waives("walk_forward")).isTrue()
        assertThat(waiver().waives("walk_forward")).isFalse()
    }

    @Test
    fun `gate booleans accept the usual spellings and fall back to their default on anything else`() {
        fun gates(value: String) =
            PromotionGateConfig
                .fromConfig(
                    mapOf("enforce" to value, "dataset_snapshot" to value, "approval" to value),
                    RuntimeMode.PRODUCTION,
                ).let { listOf(it.enforce, it.requireDatasetSnapshot, it.requireApproval) }

        for (yes in listOf("true", "1", "YES", " on ")) {
            assertThat(gates(yes)).containsExactly(true, true, true)
        }
        for (no in listOf("false", "0", "No", "off")) {
            assertThat(gates(no)).containsExactly(false, false, false)
        }
        assertThat(gates("maybe")).containsExactly(true, false, true)
    }

    @Test
    fun `an empty gate section enforces only in production and reads numbers and the registry leniently`() {
        assertThat(PromotionGateConfig.fromConfig(emptyMap(), RuntimeMode.PAPER))
            .isEqualTo(PromotionGateConfig.DISABLED)
        assertThat(PromotionGateConfig.fromConfig(emptyMap(), RuntimeMode.PRODUCTION).enforce).isTrue()

        val parsed =
            PromotionGateConfig.fromConfig(
                mapOf(
                    "required_state" to "paper",
                    "paper_days" to "-3",
                    "paper_min_trades" to "many",
                    "max_paper_slippage_bps" to "2.5",
                    "registry_dir" to "/srv/registry",
                    "walk_forward" to "yes",
                    "realistic_execution" to "1",
                ),
                RuntimeMode.DEV,
            )

        assertThat(parsed).isEqualTo(
            PromotionGateConfig(
                enforce = false,
                requiredState = PromotionState.PAPER,
                requireRealisticExecution = true,
                requireWalkForward = true,
                minPaperDays = 0,
                minPaperTrades = 0,
                maxPaperSlippageBps = 2.5,
                registryDir = Path.of("/srv/registry"),
            ),
        )
        assertThat(PromotionGateConfig.fromConfig(mapOf("registry_dir" to " "), RuntimeMode.DEV).registryDir).isNull()
    }
}
