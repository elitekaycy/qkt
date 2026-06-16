package com.qkt.marketdata.store

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes manifest mutations across both threads and OS processes that share one data root.
 *
 * Backtests can run as several independent `qkt` processes against the same mounted data directory
 * (a research pipeline fans candidates out as separate processes). Each may read a symbol's manifest,
 * fetch missing day-files, and write the manifest back. Without serialization these read-modify-write
 * cycles lose updates and a shared scratch file collides.
 *
 * The guard has two layers because neither alone is enough: an in-process re-entrant lock orders
 * threads of THIS jvm (a second OS-level lock attempt from the same jvm throws rather than blocks),
 * and an OS file lock on `<root>/.manifest.lock` orders separate processes. e.g. processes A and B
 * both provisioning XAUUSD take the file lock in turn, so neither overwrites the other's committed
 * days. The in-process lock is shared by absolute root path, so two store instances in one jvm
 * pointing at the same root still serialize instead of colliding on the file lock.
 *
 * Advisory file locks require a local filesystem; this is not safe over NFS.
 */
class ManifestLock(
    root: Path,
) {
    private val root: Path = root.toAbsolutePath().normalize()
    private val jvmLock: ReentrantLock = jvmLockFor(this.root)

    /**
     * Runs [block] while holding the cross-thread and cross-process manifest lock. Re-entrant: a
     * thread already inside `withLock` reuses the OS lock it holds rather than re-acquiring it.
     */
    fun <T> withLock(block: () -> T): T {
        jvmLock.lock()
        try {
            if (jvmLock.holdCount > 1) return block() // this thread already holds the OS lock
            Files.createDirectories(root)
            FileChannel
                .open(root.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                .use { channel ->
                    val fileLock = channel.lock()
                    try {
                        return block()
                    } finally {
                        fileLock.release()
                    }
                }
        } finally {
            jvmLock.unlock()
        }
    }

    private companion object {
        const val LOCK_FILE = ".manifest.lock"

        /** One [ReentrantLock] per absolute root, so all stores on a root share in-process ordering. */
        val jvmLocks = ConcurrentHashMap<Path, ReentrantLock>()

        fun jvmLockFor(root: Path): ReentrantLock = jvmLocks.computeIfAbsent(root) { ReentrantLock() }
    }
}
