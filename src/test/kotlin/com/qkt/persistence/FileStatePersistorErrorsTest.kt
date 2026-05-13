package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.PositionLeg
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorErrorsTest {
    private fun makeBook() =
        LegBook("XAUUSDm").apply {
            add(
                PositionLeg(
                    legId = "leg-1",
                    symbol = "XAUUSDm",
                    side = Side.BUY,
                    quantity = BigDecimal("0.1"),
                    entryPrice = BigDecimal("4700"),
                    openedAt = 0L,
                    role = LegRole.PRIMARY,
                ),
            )
        }

    @Test
    fun `saveLegBook to read-only directory logs and does not throw`(
        @TempDir tmp: Path,
    ) {
        // POSIX-only: drop write perms so the persistor encounters EACCES.
        assumeTrue(
            System.getProperty("os.name").lowercase().contains("linux") ||
                System.getProperty("os.name").lowercase().contains("mac"),
        )
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val persistor = FileStatePersistor(tmp)
            // Should not throw despite the I/O failure.
            persistor.saveLegBook("hedge", "XAUUSDm", makeBook())
        } finally {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `loadLegBook on corrupted JSON returns null without throwing`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("XAUUSDm-legbook.json"), "}}}{not even close to valid")
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadLegBook("hedge", "XAUUSDm")).isNull()
    }

    @Test
    fun `loadBracketPairs on corrupted JSON returns empty`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("bracket-pairs.json"), "garbage")
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadBracketPairs("hedge")).isEmpty()
    }

    @Test
    fun `loadPendingOrders on corrupted JSON returns empty`(
        @TempDir tmp: Path,
    ) {
        val dir = tmp.resolve("hedge")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("pending-orders.json"), "{ broken")
        val persistor = FileStatePersistor(tmp)
        assertThat(persistor.loadPendingOrders("hedge")).isEmpty()
    }
}
