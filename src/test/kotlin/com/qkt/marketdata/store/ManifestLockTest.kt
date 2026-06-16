package com.qkt.marketdata.store

import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManifestLockTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `withLock serializes a non-atomic read-modify-write across threads`() {
        val lock = ManifestLock(dir)
        val threads = 8
        val perThread = 200
        var counter = 0 // deliberately non-atomic: only the lock makes this safe
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (0 until threads).map {
                    pool.submit {
                        barrier.await()
                        repeat(perThread) {
                            lock.withLock { counter += 1 }
                        }
                    }
                }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(counter).isEqualTo(threads * perThread)
    }

    @Test
    fun `withLock is re-entrant on the same thread`() {
        val lock = ManifestLock(dir)
        var ran = false
        assertThatCode {
            lock.withLock {
                lock.withLock {
                    ran = true
                }
            }
        }.doesNotThrowAnyException()
        assertThat(ran).isTrue()
    }

    @Test
    fun `two lock instances on the same root do not collide on the os file lock`() {
        // Separate instances model separate processes; in one jvm they share the in-process lock by
        // root, so the os file lock is never double-acquired (which would throw, not block).
        val a = ManifestLock(dir)
        val b = ManifestLock(dir)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fa =
                pool.submit {
                    barrier.await()
                    repeat(100) { a.withLock { } }
                }
            val fb =
                pool.submit {
                    barrier.await()
                    repeat(100) { b.withLock { } }
                }
            assertThatCode {
                fa.get(30, TimeUnit.SECONDS)
                fb.get(30, TimeUnit.SECONDS)
            }.doesNotThrowAnyException()
        } finally {
            pool.shutdownNow()
        }
    }
}
