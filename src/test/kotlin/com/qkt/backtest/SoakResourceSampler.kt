package com.qkt.backtest

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat

/** Samples heap, thread and open-FD counts during a soak run and asserts none grew past its floor. */
internal object SoakResourceSampler {
    /** Max acceptable heap growth between first stable sample and final sample. */
    val heapGrowthBytesLimit = 50L * 1024 * 1024

    /** Allow a few transient threads (JIT compiler bursts, GC helpers). */
    val threadGrowthLimit = 4

    /** FDs should be constant once warm — paper broker opens no sockets. */
    val fdGrowthLimit = 2

    data class Snapshot(
        val elapsedMs: Long,
        val heapUsedBytes: Long,
        val threadCount: Int,
        val fdCount: Int,
        val tickCount: Long,
    )

    /**
     * Snapshot a single point in time. Calls `System.gc()` as a hint so heap
     * measurement reflects long-lived state rather than uncollected garbage —
     * leak detection is about the floor, not the peak.
     */
    fun captureSnapshot(
        elapsedMs: Long,
        tickCount: Long,
    ): Snapshot {
        System.gc()
        Thread.sleep(50)
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        return Snapshot(
            elapsedMs = elapsedMs,
            heapUsedBytes = heapUsed,
            threadCount = Thread.activeCount(),
            fdCount = countOpenFds(),
            tickCount = tickCount,
        )
    }

    /**
     * Open-FD count from `/proc/self/fd`. Returns -1 on non-Linux hosts so
     * the assertion skips the check rather than failing spuriously.
     */
    fun countOpenFds(): Int =
        try {
            val proc = Path.of("/proc/self/fd")
            if (Files.isDirectory(proc)) Files.list(proc).use { it.count().toInt() } else -1
        } catch (_: Exception) {
            -1
        }

    fun printReport(snapshots: List<Snapshot>) {
        println("BacktestSoak: ${snapshots.size} samples over ${snapshots.last().elapsedMs / 1000}s")
        println("  elapsed(s)  ticks       heap(MB)  threads  fds")
        for (s in snapshots) {
            println(
                "  %8d  %10d  %8.1f  %7d  %3d".format(
                    s.elapsedMs / 1000,
                    s.tickCount,
                    s.heapUsedBytes / (1024.0 * 1024.0),
                    s.threadCount,
                    s.fdCount,
                ),
            )
        }
    }

    /**
     * Compare the final snapshot against the second snapshot rather than the
     * first — the second sample is taken after the engine has warmed (indicator
     * buffers filled, candle history seeded), so it's the right baseline for
     * "steady state" leak detection.
     */
    fun assertResourceFloors(snapshots: List<Snapshot>) {
        require(snapshots.size >= 3) {
            "soak produced only ${snapshots.size} snapshots — duration too short or sampler broken"
        }
        val baseline = snapshots[1]
        val final = snapshots.last()

        val heapDelta = final.heapUsedBytes - baseline.heapUsedBytes
        assertThat(heapDelta)
            .withFailMessage(
                "heap grew %d bytes (%.1f MB) between baseline (t=%ds) and final (t=%ds); limit %d bytes. " +
                    "Likely an unbounded cache, position-tracker, or candle-buffer leak. ticks=%d"
                        .format(
                            heapDelta,
                            heapDelta / (1024.0 * 1024.0),
                            baseline.elapsedMs / 1000,
                            final.elapsedMs / 1000,
                            heapGrowthBytesLimit,
                            final.tickCount,
                        ),
            ).isLessThan(heapGrowthBytesLimit)

        val threadDelta = final.threadCount - baseline.threadCount
        assertThat(threadDelta)
            .withFailMessage(
                "thread count grew by %d (baseline=%d, final=%d); limit %d. " +
                    "Likely a daemon thread spawned per recurring event without reaping."
                        .format(threadDelta, baseline.threadCount, final.threadCount, threadGrowthLimit),
            ).isLessThanOrEqualTo(threadGrowthLimit)

        if (baseline.fdCount >= 0 && final.fdCount >= 0) {
            val fdDelta = final.fdCount - baseline.fdCount
            assertThat(fdDelta)
                .withFailMessage(
                    "open FD count grew by %d (baseline=%d, final=%d); limit %d. " +
                        "Likely an unclosed file or socket on a recurring path."
                            .format(fdDelta, baseline.fdCount, final.fdCount, fdGrowthLimit),
                ).isLessThanOrEqualTo(fdGrowthLimit)
        }
    }
}
