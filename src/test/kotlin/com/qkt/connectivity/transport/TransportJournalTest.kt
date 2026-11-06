package com.qkt.connectivity.transport

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TransportJournalTest {
    private fun TransportJournal.recordGet(path: String = "/v1/account") =
        record(
            method = "GET",
            path = path,
            requestBody = null,
            responseCode = 200,
            responseBody = null,
            error = null,
            durationMs = -5L,
            idempotencyKey = null,
            responseBytes = 42L,
        )

    @Test
    fun `an exchange is written as one json line under the sanitised profile`(
        @TempDir tmp: Path,
    ) {
        TransportJournal(tmp, "demo acct/1", FixedClock(86_400_000L)).use { it.recordGet() }

        val line = tmp.resolve("demo_acct_1/transport-1970-01-02.jsonl").readText().trim()
        assertThat(line)
            .startsWith("{\"v\":1,\"ts\":86400000,\"seq\":0,\"profile\":\"demo_acct_1\"")
            .contains("\"method\":\"GET\"", "\"responseBytes\":42", "\"durationMs\":0")
            .doesNotContain("engineOrderId", "requestBody", "error")
    }

    @Test
    fun `a blank profile journals under default and records after close are ignored`(
        @TempDir tmp: Path,
    ) {
        val journal = TransportJournal(tmp, " ", FixedClock(0L))
        journal.recordGet("/first")
        journal.close()
        journal.recordGet("/second")
        journal.close()

        val lines = tmp.resolve("default/transport-1970-01-01.jsonl").readLines()
        assertThat(lines).hasSize(1)
        assertThat(lines.single()).contains("\"path\":\"/first\"")
    }

    @Test
    fun `a failed append is counted as dropped and leaves a day marker`(
        @TempDir tmp: Path,
    ) {
        tmp.resolve("p/transport-1970-01-01.jsonl").createDirectories()

        TransportJournal(tmp, "p", FixedClock(0L)).use { it.recordGet() }

        val marker = tmp.resolve("p/transport-1970-01-01.dropped")
        assertThat(marker.readText()).isEqualTo("1\n")
    }

    @Test
    fun `an unwritable root drops the exchange without failing the caller`(
        @TempDir tmp: Path,
    ) {
        val root = tmp.resolve("not-a-dir").also { it.writeText("x") }
        val journal = TransportJournal(root, "p", FixedClock(0L))
        journal.recordGet()
        journal.close()

        assertThat(journal.dropped.get()).isEqualTo(1L)
        assertThat(Files.isRegularFile(root)).isTrue
    }

    @Test
    fun `a full queue drops the overflow and records how many were lost`(
        @TempDir tmp: Path,
    ) {
        val total = 2_000
        val journal = TransportJournal(tmp, "p", FixedClock(0L), queueCapacity = 1)
        repeat(total) { journal.recordGet("/n$it") }
        journal.close()

        val written = tmp.resolve("p/transport-1970-01-01.jsonl").readLines().size
        assertThat(journal.dropped.get()).isPositive
        assertThat(written + journal.dropped.get()).isEqualTo(total.toLong())
        assertThat(tmp.resolve("p/transport-1970-01-01.dropped").readText().trim())
            .isEqualTo(journal.dropped.get().toString())
    }

    @Test
    fun `json literals escape quotes backslashes and control characters`() {
        assertThat(jsonLiteral("a\"b\\c\nd\re\tf")).isEqualTo("\"a\\\"b\\\\c\\nd\\re\\tf\"")
    }
}
