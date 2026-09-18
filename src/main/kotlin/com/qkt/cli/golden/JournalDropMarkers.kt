package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fails when any `<prefix>-<day>.dropped` marker under [root] for a UTC day inside the capture
 * window records a non-zero drop count: a journal that lost records is not golden evidence.
 */
internal fun assertNoDrops(
    root: Path,
    prefix: String,
    fromMs: Long,
    toMs: Long,
) {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
    val fromDay = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate()
    val toDay = Instant.ofEpochMilli(toMs).atZone(ZoneOffset.UTC).toLocalDate()
    val markerPattern = Regex("${Regex.escape(prefix)}-(\\d{4}-\\d{2}-\\d{2})\\.dropped")
    Files.walk(root).use { stream ->
        stream
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .forEach { marker ->
                val match = markerPattern.matchEntire(marker.fileName.toString()) ?: return@forEach
                val day = LocalDate.parse(match.groupValues[1])
                if (day.isBefore(fromDay) || day.isAfter(toDay)) return@forEach
                val count =
                    Files.readString(marker).trim().toLongOrNull()
                        ?: throw IllegalArgumentException("invalid journal drop marker: $marker")
                require(count == 0L) { "$prefix journal dropped $count record(s) on $day" }
            }
    }
}
