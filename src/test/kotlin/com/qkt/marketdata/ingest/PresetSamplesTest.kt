package com.qkt.marketdata.ingest

import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.plan.PresetLibrary
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Every built-in preset's sample, in its source's real shape, imports with no flags and lands in the store
 * exactly: right preset, right UTC instant, prices at qkt's scale (bid bars shifted to mid by their spread).
 */
class PresetSamplesTest {
    @TempDir
    lateinit var dir: Path

    @ParameterizedTest(name = "{0} imports as {2}")
    @MethodSource("samples")
    fun `a sample imports with no flags to its exact stored row`(
        file: String,
        symbol: String,
        preset: String,
        firstRow: String,
        fields: Map<String, String>,
    ) {
        val root = dir.resolve("root")
        val report =
            IngestFixtures.import(
                root,
                listOf(IngestFixtures.sample(file, dir.resolve("in"))),
                symbol,
                ImportDescriptor(fields = fields),
            )

        assertThat(report.preset).isEqualTo(preset)
        assertThat(report.presetSource).isEqualTo("matched")
        assertThat(IngestFixtures.firstStoredRow(root)).isEqualTo(firstRow)
    }

    @Test
    fun `every built-in preset has a sample here`() {
        val tested = PresetSampleCases.all.map { it.get()[2] as String }.toSet()

        assertThat(PresetLibrary.builtIns.map { it.id }).allMatch { it in tested }
    }

    companion object {
        @JvmStatic
        fun samples(): List<Arguments> = PresetSampleCases.all
    }
}
