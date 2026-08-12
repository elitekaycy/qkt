package com.qkt.broker.mt5

import com.qkt.common.Clock
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Asynchronous, bounded journal of MT5 gateway HTTP exchanges used for authentic
 * golden-session capture. Authentication headers are never recorded.
 */
class MT5TransportJournal(
    private val rootDir: Path,
    profile: String,
    private val clock: Clock,
    queueCapacity: Int = 2_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(MT5TransportJournal::class.java)
    private val profileDir = profile.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private val queue = ArrayBlockingQueue<Line>(queueCapacity)
    private val sequence = AtomicLong(0L)
    private val running = AtomicBoolean(true)
    private val droppedByDay = ConcurrentHashMap<LocalDate, AtomicLong>()
    private val persistedDroppedByDay = HashMap<LocalDate, Long>()
    private var day: LocalDate? = null
    private var channel: FileChannel? = null

    /** Exchanges dropped because the bounded writer queue was full. */
    val dropped: AtomicLong = AtomicLong(0L)

    private data class Line(
        val day: LocalDate,
        val bytes: ByteArray,
    )

    private val worker =
        Thread({
            while (running.get() || queue.isNotEmpty()) {
                val line =
                    try {
                        queue.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        if (!running.get()) null else continue
                    }
                if (line != null) writeLine(line)
                persistDropMarkers()
            }
            runCatching { channel?.close() }
        }, "qkt-mt5-transport-journal-$profileDir").apply {
            isDaemon = true
            start()
        }

    /** Enqueue one completed or failed HTTP exchange without blocking the caller on disk I/O. */
    fun record(
        method: String,
        path: String,
        requestBody: String?,
        responseCode: Int?,
        responseBody: String?,
        error: String?,
        durationMs: Long,
        idempotencyKey: String?,
        engineOrderId: String? = null,
    ) {
        if (!running.get()) return
        val timestampMs = clock.now()
        val eventDay = Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate()
        val line =
            buildString {
                append("{\"v\":1")
                append(",\"ts\":").append(timestampMs)
                append(",\"seq\":").append(sequence.getAndIncrement())
                append(",\"profile\":").append(json(profileDir))
                append(",\"method\":").append(json(method))
                append(",\"path\":").append(json(path))
                idempotencyKey?.let { append(",\"idempotencyKey\":").append(json(it)) }
                engineOrderId?.let { append(",\"engineOrderId\":").append(json(it)) }
                requestBody?.let { append(",\"requestBody\":").append(json(it)) }
                responseCode?.let { append(",\"responseCode\":").append(it) }
                responseBody?.let { append(",\"responseBody\":").append(json(it)) }
                error?.let { append(",\"error\":").append(json(it)) }
                append(",\"durationMs\":").append(durationMs.coerceAtLeast(0L))
                append("}\n")
            }.toByteArray(StandardCharsets.UTF_8)
        if (!queue.offer(Line(eventDay, line))) {
            val count = markDropped(eventDay)
            if (count == 1L || count % 1_000L == 0L) {
                log.error("MT5 transport journal queue full for {}; dropped {} exchange(s)", profileDir, count)
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.join(5_000L)
        if (worker.isAlive) {
            worker.interrupt()
            log.error("MT5 transport journal did not drain for {}; remaining={}", profileDir, queue.size)
        }
    }

    private fun writeLine(line: Line) {
        try {
            val buffer = ByteBuffer.wrap(line.bytes)
            val output = channelFor(line.day)
            while (buffer.hasRemaining()) output.write(buffer)
        } catch (error: Exception) {
            markDropped(line.day)
            log.error("MT5 transport journal append failed for {}: {}", profileDir, error.message)
            runCatching { channel?.close() }
            channel = null
            day = null
        }
    }

    private fun markDropped(eventDay: LocalDate): Long {
        droppedByDay.computeIfAbsent(eventDay) { AtomicLong(0L) }.incrementAndGet()
        return dropped.incrementAndGet()
    }

    private fun persistDropMarkers() {
        for ((eventDay, countRef) in droppedByDay) {
            val count = countRef.get()
            if (persistedDroppedByDay[eventDay] == count) continue
            try {
                val dir = rootDir.resolve(profileDir)
                createPrivateDirectory(dir)
                val marker = dir.resolve("transport-$eventDay.dropped")
                Files.writeString(
                    marker,
                    "$count\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.DSYNC,
                )
                makeOwnerOnly(marker)
                persistedDroppedByDay[eventDay] = count
            } catch (error: Exception) {
                log.error("MT5 transport journal could not persist drop marker for {}: {}", profileDir, error.message)
            }
        }
    }

    private fun channelFor(eventDay: LocalDate): FileChannel {
        val open = channel
        if (open != null && day == eventDay) return open
        runCatching { open?.close() }
        val dir = rootDir.resolve(profileDir)
        createPrivateDirectory(dir)
        val path = dir.resolve("transport-$eventDay.jsonl")
        return FileChannel
            .open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC,
            ).also {
                makeOwnerOnly(path)
                channel = it
                day = eventDay
            }
    }

    private fun createPrivateDirectory(path: Path) {
        Files.createDirectories(path)
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
    }

    private fun makeOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun json(value: String): String =
        buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
}
