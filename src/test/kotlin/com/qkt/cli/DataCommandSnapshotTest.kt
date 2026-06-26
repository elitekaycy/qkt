package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataCommandSnapshotTest {
    private fun seedDay(
        root: Path,
        symbol: String,
        day: String,
        rows: List<String>,
    ) {
        val dir = root.resolve("symbols").resolve(symbol)
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("$day.csv"),
            (listOf("timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume") + rows).joinToString("\n") + "\n",
        )
    }

    @Test
    fun `snapshot output is deterministic and strict verify succeeds`(
        @TempDir root: Path,
    ) {
        seedDay(
            root,
            "XAUUSD",
            "2024-01-01",
            listOf(
                "1704067200000,XAUUSD,2000.00000000,1.00000000,1999.90000000,2000.10000000,,",
                "1704067260000,XAUUSD,2001.00000000,2.00000000,2000.90000000,2001.10000000,,",
            ),
        )
        val first = root.resolve("snapshot-1.json")
        val second = root.resolve("snapshot-2.json")

        assertThat(snapshot(root, first)).isEqualTo(ExitCodes.SUCCESS)
        assertThat(snapshot(root, second)).isEqualTo(ExitCodes.SUCCESS)

        val json = Files.readString(first)
        assertThat(json).isEqualTo(Files.readString(second))
        assertThat(json).contains("\"id\": \"qkt-ds-xauusd-2024-01-01_2024-01-02-")
        assertThat(json).contains("\"path\": \"symbols/XAUUSD/2024-01-01.csv\"")
        assertThat(json).contains("\"format\": \"csv\"")
        assertThat(json).contains("\"lineage\": \"local tick day file (csv)\"")
        assertThat(json).contains("\"tickCount\": 2")
        assertThat(json).contains("\"bidAskTicks\": 2")
        assertThat(json).contains("\"volumeTicks\": 2")

        assertThat(verifySnapshot(root, first)).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `strict snapshot verify fails when a day file changes`(
        @TempDir root: Path,
    ) {
        seedDay(
            root,
            "XAUUSD",
            "2024-01-01",
            listOf("1704067200000,XAUUSD,2000.00000000,1.00000000,1999.90000000,2000.10000000,,"),
        )
        val out = root.resolve("snapshot.json")
        assertThat(snapshot(root, out)).isEqualTo(ExitCodes.SUCCESS)

        seedDay(
            root,
            "XAUUSD",
            "2024-01-01",
            listOf(
                "1704067200000,XAUUSD,2000.00000000,1.00000000,1999.90000000,2000.10000000,,",
                "1704067260000,XAUUSD,2001.00000000,1.00000000,2000.90000000,2001.10000000,,",
            ),
        )

        assertThat(verifySnapshot(root, out)).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `strict snapshot verify fails when a day file is missing`(
        @TempDir root: Path,
    ) {
        seedDay(
            root,
            "XAUUSD",
            "2024-01-01",
            listOf("1704067200000,XAUUSD,2000.00000000,1.00000000,1999.90000000,2000.10000000,,"),
        )
        val out = root.resolve("snapshot.json")
        assertThat(snapshot(root, out)).isEqualTo(ExitCodes.SUCCESS)

        Files.delete(root.resolve("symbols").resolve("XAUUSD").resolve("2024-01-01.csv"))

        assertThat(verifySnapshot(root, out)).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `strict snapshot verify fails when a day file becomes corrupt`(
        @TempDir root: Path,
    ) {
        seedDay(
            root,
            "XAUUSD",
            "2024-01-01",
            listOf("1704067200000,XAUUSD,2000.00000000,1.00000000,1999.90000000,2000.10000000,,"),
        )
        val out = root.resolve("snapshot.json")
        assertThat(snapshot(root, out)).isEqualTo(ExitCodes.SUCCESS)

        Files.writeString(root.resolve("symbols").resolve("XAUUSD").resolve("2024-01-01.csv"), "wrong,header\n1,X\n")

        assertThat(verifySnapshot(root, out)).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `strict snapshot verify fails on empty day unless policy allows it`(
        @TempDir root: Path,
    ) {
        seedDay(root, "XAUUSD", "2024-01-01", emptyList())
        val out = root.resolve("snapshot.json")
        assertThat(snapshot(root, out)).isEqualTo(ExitCodes.SUCCESS)

        assertThat(verifySnapshot(root, out)).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `strict snapshot verify fails when max intraday gap exceeds policy`(
        @TempDir root: Path,
    ) {
        seedDay(
            root,
            "XAUUSD",
            "2024-01-01",
            listOf(
                "1704067200000,XAUUSD,2000.00000000,1.00000000,1999.90000000,2000.10000000,,",
                "1704074400000,XAUUSD,2001.00000000,1.00000000,2000.90000000,2001.10000000,,",
            ),
        )
        val out = root.resolve("snapshot.json")
        assertThat(snapshot(root, out)).isEqualTo(ExitCodes.SUCCESS)

        assertThat(verifySnapshot(root, out)).isEqualTo(ExitCodes.USER_ERROR)
    }

    private fun snapshot(
        root: Path,
        out: Path,
    ): Int =
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "snapshot",
                    "XAUUSD",
                    "--from",
                    "2024-01-01",
                    "--to",
                    "2024-01-02",
                    "--vendor",
                    "test",
                    "--data-root",
                    root.toString(),
                    "--out",
                    out.toString(),
                ),
            ),
        ).run()

    private fun verifySnapshot(
        root: Path,
        snapshot: Path,
    ): Int =
        DataCommand(
            Args(
                arrayOf(
                    "data",
                    "verify",
                    "--snapshot",
                    snapshot.toString(),
                    "--strict",
                    "--data-root",
                    root.toString(),
                ),
            ),
        ).run()
}
