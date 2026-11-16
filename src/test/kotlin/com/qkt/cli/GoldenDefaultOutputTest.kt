package com.qkt.cli

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenDefaultOutputTest {
    @Test
    fun `a capture with no output path writes a zip named for the session and the clock`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """{"v":1,"ts":1100,"seq":1,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:XAUUSD","tick":{"timestampMs":310000,"price":"2001"}}""" +
                "\n",
        )
        Files.writeString(transport, """{"v":1,"ts":1100,"method":"GET","path":"/account","responseCode":200}""" + "\n")
        val expected = Path.of("qkt-golden-alpha-20250809T120000Z.zip")

        try {
            val code =
                GoldenCommand(
                    Args(
                        arrayOf(
                            "golden",
                            "capture",
                            "--session",
                            "alpha",
                            "--state-dir",
                            tmp.toString(),
                            "--read-only",
                        ),
                    ),
                    clock = FixedClock(1_754_740_800_000L),
                ).run()

            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            assertThat(expected).isRegularFile()
        } finally {
            Files.deleteIfExists(expected)
        }
    }
}
