package com.qkt.evidence

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvidenceTest {
    @Test
    fun `sha256 hashes file contents with stable prefix`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("strategy.qkt")
        Files.writeString(file, "hello\n")

        assertThat(EvidenceHasher.sha256(file))
            .isEqualTo("sha256:5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03")
    }

    @Test
    fun `json renderer sorts maps and escapes strings`() {
        val evidence =
            EvidenceEnvelope(
                qktVersion = "1.0",
                gitSha = "abc",
                buildTimestamp = "2026-06-25T00:00:00Z",
                command = listOf("backtest", "s \"one\".qkt"),
                strategyHash = "sha256:strategy",
                importedFileHashes = mapOf("z" to "sha256:z", "a" to "sha256:a"),
                dataset = DatasetEvidence(mutableStore = true, warning = "not\npinned"),
                execution = ExecutionEvidence(preset = "paper-fast", broker = "paper"),
            )

        val json = EvidenceJson.render(evidence)

        assertThat(json).contains("\"command\":[\"backtest\",\"s \\\"one\\\".qkt\"]")
        assertThat(json).contains("\"importedFileHashes\":{\"a\":\"sha256:a\",\"z\":\"sha256:z\"}")
        assertThat(json).contains("\"warning\":\"not\\npinned\"")
        assertThat(json).contains("\"execution\":{\"preset\":\"paper-fast\",\"broker\":\"paper\"")
    }
}
