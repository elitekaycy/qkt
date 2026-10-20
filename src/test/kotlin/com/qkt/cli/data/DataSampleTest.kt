package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataSampleTest {
    /** One day (2026-09-01) of Binance spot klines in the archive's layout: µs open times, no header. */
    private fun klinesZip(): ByteArray {
        val start = 1_788_220_800_000_000L
        val csv =
            (0 until 1440).joinToString("\n", postfix = "\n") { i ->
                val t = start + i * 60_000_000L
                "$t,78000.00,78010.00,77990.00,78005.00,1.5,${t + 59_999_999},117000.0,42,0.7,54600.0,0"
            }
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("BTCUSDT-1m-2026-09.csv"))
            zip.write(csv.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun run(
        root: Path,
        sample: SampleData,
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out))
        val code =
            try {
                dataSample(Args(arrayOf("data", "sample", "--data-root", root.toString())), sample, OkHttpClient())
            } finally {
                System.setOut(original)
            }
        return code to out.toString()
    }

    private fun sample(
        server: MockWebServer,
        sha: String,
    ) = SampleData(
        url = server.url("/data/BTCUSDT-1m-2026-09.zip").toString(),
        sha256 = sha,
        symbol = "BINANCE:BTCUSDT",
        window = "--from 2026-09-01 --to 2026-09-02",
        terms = "test terms",
    )

    @Test
    fun `downloads the pinned file once, imports it, and reuses the checked copy`(
        @TempDir root: Path,
    ) {
        val zip = klinesZip()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        server.start()

        val (first, text) = run(root, sample(server, hex(zip)))
        val (second, _) = run(root, sample(server, hex(zip)))

        assertThat(first).isEqualTo(ExitCodes.SUCCESS)
        assertThat(second).isEqualTo(ExitCodes.SUCCESS)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(root.resolve("bars/BINANCE/BTCUSDT/1m")).isDirectory()
        assertThat(text).contains("Wrote    1 day(s)").contains("test terms")
        server.shutdown()
    }

    @Test
    fun `a download that does not match its pinned hash imports nothing and keeps no file`(
        @TempDir root: Path,
    ) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(klinesZip())))
        server.start()

        val (code, _) = run(root, sample(server, "0".repeat(64)))

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(Files.exists(root.resolve("bars"))).isFalse()
        assertThat(Files.list(root.resolve("samples")).use { it.count() }).isZero()
        server.shutdown()
    }
}
