package com.qkt.cli.soak

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The soak's reconciliation and generic JSON evidence checks. */
class SoakJsonEvidenceTest {
    @TempDir
    lateinit var tmp: Path

    private fun file(
        name: String,
        text: String,
    ): Path = Files.writeString(tmp.resolve(name), text)

    @Test
    fun `reconciliation passes only when it says clean true`() {
        assertThatCode { inspectReconciliation(file("clean.json", """{"clean":true}""")) }.doesNotThrowAnyException()
        for ((name, text) in mapOf(
            "dirty.json" to """{"clean":false}""",
            "missing.json" to "{}",
            "text.json" to """{"clean":"yes"}""",
        )) {
            assertThatThrownBy { inspectReconciliation(file(name, text)) }
                .hasMessage("final engine-to-venue reconciliation is not clean")
        }
    }

    @Test
    fun `evidence that is not a json object is refused with its position`() {
        val broken = file("coverage.json", "[1, 2")

        assertThatThrownBy { inspectJsonArtifact(broken, "coverage") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("malformed JSON at $broken:1: ")
        assertThatThrownBy { inspectReconciliation(file("array.json", "[]")) }
            .hasMessageStartingWith("malformed JSON at ")
        assertThatCode { inspectJsonArtifact(file("insights.json", """{"ok":1}"""), "insights") }
            .doesNotThrowAnyException()
    }
}
