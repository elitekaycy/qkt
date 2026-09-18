package com.qkt.marketdata.store.macro

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads one policy-rate source artifact as bytes: an HTTP(S) download through [http], or an
 * operator-managed local copy given as a `file:` URI or a plain path. Either way the artifact
 * must be non-empty and at most [MAX_ARTIFACT_BYTES]; [authority] names the bank in errors.
 */
internal class PolicyRateArtifactReader(
    private val http: OkHttpClient,
) {
    fun read(
        source: String,
        authority: String,
    ): ByteArray {
        val scheme = runCatching { URI(source).scheme?.lowercase() }.getOrNull()
        return when (scheme) {
            "http", "https" -> download(source, authority)
            "file" -> readFile(Path.of(URI(source)), authority, source)
            null -> readFile(Path.of(source), authority, source)
            else -> error("$authority policy-rate source uses unsupported URI scheme '$scheme': $source")
        }
    }

    private fun download(
        url: String,
        authority: String,
    ): ByteArray =
        http
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "qkt-policy-rate-fetcher/1")
                    .header("Accept", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .build(),
            ).execute()
            .use { response ->
                check(response.isSuccessful) {
                    "$authority policy-rate fetch failed: HTTP ${response.code} from $url"
                }
                val body = response.body ?: error("$authority policy-rate fetch returned an empty body")
                check(body.contentLength() <= MAX_ARTIFACT_BYTES || body.contentLength() == -1L) {
                    "$authority policy-rate artifact exceeds $MAX_ARTIFACT_BYTES bytes: $url"
                }
                val bytes = body.byteStream().readNBytes(MAX_ARTIFACT_BYTES + 1)
                check(bytes.isNotEmpty()) { "$authority policy-rate fetch returned an empty body" }
                check(bytes.size <= MAX_ARTIFACT_BYTES) {
                    "$authority policy-rate artifact exceeds $MAX_ARTIFACT_BYTES bytes: $url"
                }
                bytes
            }

    private fun readFile(
        path: Path,
        authority: String,
        source: String,
    ): ByteArray {
        check(Files.isRegularFile(path)) { "$authority policy-rate artifact is not a regular file: $source" }
        check(Files.size(path) in 1..MAX_ARTIFACT_BYTES.toLong()) {
            "$authority policy-rate artifact must contain 1..$MAX_ARTIFACT_BYTES bytes: $source"
        }
        return Files.readAllBytes(path)
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES = 10 * 1024 * 1024
    }
}
