package com.qkt.evidence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EvidenceJsonRenderTest {
    private val bare = EvidenceEnvelope(qktVersion = "0.66.0", gitSha = "abc", buildTimestamp = "t", strategyHash = "h")

    @Test
    fun `an envelope without optional evidence renders each section as null`() {
        val json = EvidenceJson.render(bare)

        assertThat(json)
            .contains("\"configHash\":null", "\"dataset\":null", "\"execution\":null")
            .contains("\"experiment\":null", "\"accounting\":null", "\"promotion\":null")
            .contains("\"resolved\":{}", "\"warnings\":[]")
    }

    @Test
    fun `sections with unset fields render them as null`() {
        val json =
            EvidenceJson.render(
                bare.copy(
                    configHash = "cfg",
                    dataset = DatasetEvidence(),
                    execution = ExecutionEvidence(preset = "default", broker = "sim"),
                    experiment = ExperimentEvidence(),
                    accounting = AccountingEvidence(),
                    promotion = PromotionEvidence(),
                    resolved = mapOf("b" to ResolvedValue("2", "cli"), "a" to ResolvedValue("1", "config")),
                ),
            )

        assertThat(json)
            .contains("\"configHash\":\"cfg\"")
            .contains(
                "\"dataset\":{\"id\":null,\"hash\":null,\"qualityPolicy\":null,\"mutableStore\":false,\"warning\":null}",
            ).contains("\"seed\":null", "\"trialCount\":null", "\"eligibleForProduction\":null")
            .contains(
                "\"resolved\":{\"a\":{\"value\":\"1\",\"from\":\"config\"},\"b\":{\"value\":\"2\",\"from\":\"cli\"}}",
            ).doesNotContain("barsWithoutTicks")
    }

    @Test
    fun `set fields and bars without ticks are rendered`() {
        val json =
            EvidenceJson.render(
                bare.copy(
                    execution =
                        ExecutionEvidence(
                            preset = "default",
                            broker = "sim",
                            seed = 42L,
                            barsWithoutTicks = listOf(BarsWithoutTicks("XAUUSD", 2, 10, 0)),
                        ),
                    experiment = ExperimentEvidence(trialCount = 7),
                    promotion = PromotionEvidence(eligibleForProduction = true),
                ),
            )

        assertThat(json)
            .contains("\"seed\":42", "\"trialCount\":7", "\"eligibleForProduction\":true")
            .contains(
                "\"barsWithoutTicks\":[{\"symbol\":\"XAUUSD\",\"withoutTicks\":2,\"fillPossible\":10," +
                    "\"allWithoutTicks\":0,\"fill\":\"level\"}]",
            )
    }
}
