package com.qkt.marketdata.hub

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Catching a bad hub reference before a run starts.
 *
 * The failure this prevents is quiet rather than loud: a mistyped field is simply undefined for
 * the whole backtest, so the rule referencing it never fires and the report reads as a strategy
 * that found no setups. That is indistinguishable from an honest result, which is why it has to
 * be an error at setup instead.
 */
class HubManifestTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/$name")) { "missing fixture $name" }.use { it.readAllBytes() }

    private fun store(
        root: Path,
        withManifest: Boolean = true,
    ): Path {
        val dir = root.resolve("journal").resolve("cal.high_impact")
        dir.createDirectories()
        dir.resolve("2026-09-10.ndjson").writeBytes(fixture("cal.high_impact.ndjson"))
        if (withManifest) root.resolve("manifest.json").writeBytes(fixture("manifest.json"))
        return root
    }

    private fun symbol(
        dataset: String,
        scope: String?,
        field: String,
    ) = HubStreamSymbol(dataset, scope, field).symbol

    @Test
    fun `the manifest lists a dataset's readable fields`(
        @TempDir tmp: Path,
    ) {
        val manifest = checkNotNull(HubManifest.load(store(tmp)))
        val dataset = checkNotNull(manifest.datasets["cal.high_impact"])

        assertThat(dataset.scopeKind).isEqualTo("currency")
        assertThat(dataset.schemaHash).startsWith("sha256:")
        assertThat(dataset.strategyFieldNames())
            .containsExactlyInAnyOrder("impact", "forecast", "previous", "actual", "surprise", "surprise_z")
    }

    @Test
    fun `a free-text field is not offered to a strategy`(
        @TempDir tmp: Path,
    ) {
        // `title` exists in the dataset but is prose. A rule comparing against it would be
        // comparing against a sentence, so it is excluded from what a strategy may name.
        val dataset = checkNotNull(HubManifest.load(store(tmp))?.datasets?.get("cal.high_impact"))
        assertThat(dataset.fields.map { it.name }).contains("title")
        assertThat(dataset.strategyFieldNames()).doesNotContain("title")
    }

    @Test
    fun `every declared field resolves`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val declared = listOf(symbol("cal.high_impact", "USD", "surprise"), symbol("cal.high_impact", "USD", "actual"))
        assertThat(validateHubStreams(root, declared)).isEmpty()
    }

    @Test
    fun `a mistyped field is reported with the fields that do exist`(
        @TempDir tmp: Path,
    ) {
        val root = store(tmp)
        val problems = validateHubStreams(root, listOf(symbol("cal.high_impact", "USD", "surpirse")))

        assertThat(problems).hasSize(1)
        assertThat(problems.single()).contains("surpirse").contains("surprise").contains("forecast")
    }

    @Test
    fun `naming a free-text field is rejected like any unknown one`(
        @TempDir tmp: Path,
    ) {
        val problems = validateHubStreams(store(tmp), listOf(symbol("cal.high_impact", "USD", "title")))
        assertThat(problems).hasSize(1)
        assertThat(problems.single()).contains("title")
    }

    @Test
    fun `an unknown dataset is reported with the datasets that do exist`(
        @TempDir tmp: Path,
    ) {
        val problems = validateHubStreams(store(tmp), listOf(symbol("macro.us.nope", "USD", "actual")))
        assertThat(problems).hasSize(1)
        assertThat(problems.single()).contains("macro.us.nope").contains("cal.high_impact")
    }

    @Test
    fun `a missing store is reported rather than silently producing nothing`(
        @TempDir tmp: Path,
    ) {
        val problems = validateHubStreams(tmp.resolve("absent"), listOf(symbol("cal.high_impact", "USD", "actual")))
        assertThat(problems).hasSize(1)
        assertThat(problems.single()).contains("hub store not found").contains(HubMarketSource.ROOT_ENV)
    }

    @Test
    fun `a store that has never compiled is not treated as an error`(
        @TempDir tmp: Path,
    ) {
        // The journal is authoritative and a freshly collected store may have no manifest yet.
        // Reporting what cannot be known would block a legitimate run.
        val root = store(tmp, withManifest = false)
        assertThat(Files.exists(root.resolve("manifest.json"))).isFalse()
        assertThat(validateHubStreams(root, listOf(symbol("cal.high_impact", "USD", "actual")))).isEmpty()
    }

    @Test
    fun `a run that declares no hub stream is never checked`(
        @TempDir tmp: Path,
    ) {
        assertThat(validateHubStreams(tmp.resolve("absent"), listOf("EXNESS:XAUUSD", "BACKTEST:BTCUSDT"))).isEmpty()
    }
}
