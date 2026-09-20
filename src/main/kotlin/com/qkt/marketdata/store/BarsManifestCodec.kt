package com.qkt.marketdata.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for the bar store's `manifest.json`: pretty-printed, strict about unknown keys,
 * and rejecting a corrupt file or an unsupported schema with the file's [path] in the error.
 */
internal object BarsManifestCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = false
        }

    fun decode(
        path: java.nio.file.Path,
        text: String,
    ): BarsManifest {
        val manifest =
            try {
                json.decodeFromString<BarsManifest>(text)
            } catch (e: Exception) {
                error("corrupt bars manifest at $path: ${e.message}")
            }
        require(manifest.schemaVersion == 1) {
            "unsupported bars manifest schemaVersion at $path: ${manifest.schemaVersion}"
        }
        require(manifest.schema == "qkt-bars-csv-v1") {
            "unsupported bars manifest schema at $path: ${manifest.schema}"
        }
        return manifest
    }

    fun encode(manifest: BarsManifest): String = json.encodeToString(manifest)
}
