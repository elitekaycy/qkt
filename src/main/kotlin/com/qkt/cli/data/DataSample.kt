package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.CliDataRoot
import com.qkt.cli.ExitCodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The quick start's practice data: one month of Binance spot BTCUSDT one-minute klines, fetched from Binance's
 * public archive and checked against a pinned SHA-256, so every machine imports the same bytes and the quick start's
 * numbers come out the same everywhere. qkt does not ship the file: Binance publishes it under its own terms.
 */
internal data class SampleData(
    val url: String,
    val sha256: String,
    val symbol: String,
    val window: String,
    val terms: String,
) {
    val fileName: String get() = url.substringAfterLast('/')

    companion object {
        val BTCUSDT_2026_09 =
            SampleData(
                url = "https://data.binance.vision/data/spot/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-2026-09.zip",
                sha256 = "7a021fb5981cfdb1a56457d68fa81360ab27350c981ddfe63112b2322df44b56",
                symbol = "BINANCE:BTCUSDT",
                window = "--from 2026-09-01 --to 2026-10-01",
                terms =
                    "Binance public data (data.binance.vision), under Binance's terms: " +
                        "https://data.binance.vision/Binance_Vision-Terms_of_Use.pdf",
            )
    }
}

/**
 * `qkt data sample [--data-root DIR]`: downloads the sample once into `<data root>/samples/`, checks its SHA-256,
 * and imports it as [SampleData.symbol] with `qkt data import`. A copy already there with the right hash is reused.
 */
internal fun dataSample(
    args: Args,
    sample: SampleData = SampleData.BTCUSDT_2026_09,
    http: OkHttpClient = defaultClient(),
): Int {
    val dataRoot = CliDataRoot.of(args)
    val file = dataRoot.resolve("samples").resolve(sample.fileName)
    if (sha256(file) != sample.sha256) {
        System.err.println("qkt: downloading ${sample.url}")
        val failure = download(http, sample, file)
        if (failure != null) {
            System.err.println("qkt: $failure")
            return ExitCodes.USER_ERROR
        }
    }
    System.err.println("qkt: ${sample.fileName} sha256 ${sample.sha256.take(12)}… matches")
    val code =
        dataImport(
            Args(arrayOf("data", "import", file.toString(), "--as", sample.symbol, "--data-root", dataRoot.toString())),
        )
    if (code == ExitCodes.SUCCESS) {
        println("Data:  ${sample.terms}")
        println("Next:  qkt backtest <strategy.qkt> ${sample.window} --bars   (stream ${sample.symbol})")
    }
    return code
}

/** Fetches [sample] to [dest] through a temporary file, or returns why it could not; never leaves a bad file. */
private fun download(
    http: OkHttpClient,
    sample: SampleData,
    dest: Path,
): String? {
    Files.createDirectories(dest.parent)
    val part = dest.resolveSibling("${dest.fileName}.part")
    try {
        http.newCall(Request.Builder().url(sample.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return "the download failed: HTTP ${resp.code} from ${sample.url}"
            val body = resp.body ?: return "the download failed: empty answer from ${sample.url}"
            body.byteStream().use { Files.copy(it, part, StandardCopyOption.REPLACE_EXISTING) }
        }
        val got = sha256(part)
        if (got != sample.sha256) {
            return "the download does not match its pinned sha256 (got $got, want ${sample.sha256}); nothing imported"
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return null
    } catch (e: java.io.IOException) {
        return "the download failed: ${e.message} (${sample.url}); check the connection and run it again"
    } finally {
        Files.deleteIfExists(part)
    }
}

private fun sha256(file: Path): String? {
    if (!Files.isRegularFile(file)) return null
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun defaultClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(60))
        .build()
