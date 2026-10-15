package com.qkt.marketdata.ingest

import com.qkt.common.FixedClock
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import com.qkt.marketdata.ingest.run.ImportReport
import com.qkt.marketdata.ingest.run.ImportRequest
import com.qkt.marketdata.ingest.run.ImportRun
import com.qkt.marketdata.ingest.run.OverlapPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Shared helpers for import tests: sample files, a fixed clock, and reading back what the store holds. */
object IngestFixtures {
    /** 2026-10-15T00:00Z: later than every sample, so epoch range checks pass deterministically. */
    const val NOW_MS = 1_792_022_400_000L
    private const val SAMPLES = "/com/qkt/marketdata/ingest/samples/"

    /** Copies the sample [name] into [dir] under its own file name (file names matter to preset matching). */
    fun sample(
        name: String,
        dir: Path,
    ): Path {
        val stream = IngestFixtures::class.java.getResourceAsStream("$SAMPLES$name") ?: error("no sample $name")
        val bytes = stream.use { it.readAllBytes() }
        Files.createDirectories(dir)
        return dir.resolve(name).also { Files.write(it, bytes) }
    }

    /** Runs an import of [paths] as [symbol] into [root]. */
    fun import(
        root: Path,
        paths: List<Path>,
        symbol: String,
        overrides: ImportDescriptor = ImportDescriptor(),
        preset: String? = null,
        dryRun: Boolean = false,
        onOverlap: OverlapPolicy = OverlapPolicy.REFUSE,
        sort: Boolean = false,
        trustClock: Boolean = false,
    ): ImportReport =
        ImportRun(
            ImportRequest(
                paths = paths,
                symbol = symbol,
                dataRoot = root,
                overrides = overrides,
                preset = preset,
                dryRun = dryRun,
                onOverlap = onOverlap,
                sort = sort,
                trustClock = trustClock,
                qktVersion = "test",
            ),
            StandardInstrumentRegistry,
            FixedClock(NOW_MS),
        ).run()

    /** Every data file under [root] (day files and point files, not manifests or provenance), sorted by path. */
    fun dataFiles(root: Path): List<Path> =
        Files.walk(root).use { s ->
            s
                .filter { Files.isRegularFile(it) }
                .filter { p -> root.relativize(p).none { it.toString().startsWith('.') } }
                .filter { it.fileName.toString() != "manifest.json" }
                .sorted()
                .toList()
        }

    /** The lines of a stored file, gunzipped when it is a `.gz`. */
    fun lines(file: Path): List<String> =
        if (file.toString().endsWith(".gz")) {
            GZIPInputStream(Files.newInputStream(file)).bufferedReader().readLines()
        } else {
            Files.readAllLines(file)
        }

    /** The first data row of the first non-empty stored file under [root]. */
    fun firstStoredRow(root: Path): String = dataFiles(root).map(::lines).first { it.size > 1 }[1]
}
