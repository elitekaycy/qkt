package com.qkt.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StateFileWriterTest {
    @Test
    fun `write then read returns identical content`(
        @TempDir tmp: Path,
    ) {
        val w = StateFileWriter(tmp)
        w.write("hedge", "legbook.json", """{"version":1}""")
        assertThat(w.read("hedge", "legbook.json")).isEqualTo("""{"version":1}""")
    }

    @Test
    fun `read missing file returns null`(
        @TempDir tmp: Path,
    ) {
        val w = StateFileWriter(tmp)
        assertThat(w.read("absent", "legbook.json")).isNull()
    }

    @Test
    fun `deleteStrategy removes the entire directory`(
        @TempDir tmp: Path,
    ) {
        val w = StateFileWriter(tmp)
        w.write("hedge", "legbook.json", "x")
        w.write("hedge", "bracket-pairs.json", "y")
        w.deleteStrategy("hedge")
        assertThat(Files.exists(tmp.resolve("hedge"))).isFalse
    }

    @Test
    fun `concurrent reads while writing never see a torn file`(
        @TempDir tmp: Path,
    ) {
        val w = StateFileWriter(tmp)
        // Seed an initial valid version.
        val v1 = """{"v":1,"x":"a"}"""
        val v2 = """{"v":2,"x":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}"""
        w.write("hedge", "legbook.json", v1)

        val stop = AtomicBoolean(false)
        val readerCount = 16
        val tornReads = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(readerCount + 1)
        val ready = CountDownLatch(readerCount + 1)
        try {
            repeat(readerCount) {
                pool.submit {
                    ready.countDown()
                    ready.await()
                    while (!stop.get()) {
                        val s = w.read("hedge", "legbook.json")
                        // Each read must yield one of the two complete versions, never partial.
                        if (s != null && s != v1 && s != v2) tornReads.incrementAndGet()
                    }
                }
            }
            pool.submit {
                ready.countDown()
                ready.await()
                repeat(200) { i ->
                    w.write("hedge", "legbook.json", if (i % 2 == 0) v1 else v2)
                }
                stop.set(true)
            }
            pool.shutdown()
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue
            assertThat(tornReads.get()).isZero
        } finally {
            pool.shutdownNow()
        }
    }
}
