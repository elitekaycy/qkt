package com.qkt.cli.golden

import com.qkt.common.Clock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Counts reported after a golden bundle is materialized into replay stores. */
internal data class GoldenReplayDataSummary(
    val liveTicks: Long,
    val warmupTicks: Long,
    val candles: Long,
)

/** Converts verified golden market records into the normal QKT tick and bar stores. */
internal class GoldenReplayDataMaterializer(
    private val bundle: Path,
    private val output: Path,
    private val clock: Clock,
) {
    /** Verify the bundle, build the stores in a private temp directory, then move it into place. */
    fun materialize(): GoldenReplayDataSummary {
        require(Files.isRegularFile(bundle)) { "golden bundle not found: $bundle" }
        val absolute = output.toAbsolutePath().normalize()
        require(!Files.exists(absolute)) { "output already exists: $absolute" }
        val parent = absolute.parent ?: error("output has no parent: $absolute")
        Files.createDirectories(parent)
        val temp = Files.createTempDirectory(parent, ".${absolute.fileName}.")
        makePrivateDirectory(temp)
        try {
            val capture = GoldenBundleReader(bundle).read()
            val replayCandles = replayCandles(capture)
            val stores = GoldenReplayStoreWriter(clock)
            stores.writeTickStore(temp, capture.ticks)
            stores.writeBarStores(temp, replayCandles)
            GoldenReplayManifestWriter(bundle, clock).write(temp, capture, replayCandles)
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, absolute)
            }
            return GoldenReplayDataSummary(
                liveTicks = capture.ticks.count { !it.warmup }.toLong(),
                warmupTicks = capture.ticks.count { it.warmup }.toLong(),
                candles = capture.candles.size.toLong(),
            )
        } finally {
            if (Files.exists(temp)) deleteTree(temp)
        }
    }
}
