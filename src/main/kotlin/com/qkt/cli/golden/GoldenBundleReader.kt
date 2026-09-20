package com.qkt.cli.golden

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Opens a golden bundle, verifies every manifest entry's path, hash and record count, then reads
 * the engine journals into a [GoldenMarketCapture]. Any mismatch fails closed.
 */
internal class GoldenBundleReader(
    private val bundle: Path,
) {
    /** Verify the bundle and return its market records. */
    fun read(): GoldenMarketCapture {
        ZipFile(bundle.toFile()).use { zip ->
            val manifestEntry =
                zip.getEntry("manifest.json")
                    ?: throw IllegalArgumentException("golden bundle has no manifest.json")
            val manifest =
                zip.getInputStream(manifestEntry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    parseObject(reader.readText(), "manifest.json", 1L)
                }
            require(manifest["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 2) {
                "unsupported golden schemaVersion"
            }
            require(manifest["kind"]?.jsonPrimitive?.contentOrNull == "MT5_GOLDEN_CAPTURE") {
                "unsupported golden bundle kind"
            }
            val entries =
                manifest["entries"]?.jsonArray
                    ?: throw IllegalArgumentException("golden bundle has no entries")
            val names = mutableSetOf<String>()
            val engineNames = mutableListOf<String>()
            for (element in entries) {
                val evidence = element.jsonObject
                val name = requireText(evidence, "path", "manifest.json", 1L)
                require(names.add(name)) { "duplicate golden entry: $name" }
                require(name.startsWith("engine/") || name.startsWith("orders/") || name.startsWith("gateway/")) {
                    "unsupported golden entry path: $name"
                }
                require(!name.startsWith('/') && name.split('/').none { it == ".." || it.isBlank() }) {
                    "unsafe golden entry path: $name"
                }
                val expectedRecords = requireLong(evidence, "records", "manifest.json", 1L)
                val expectedHash = requireText(evidence, "sha256", "manifest.json", 1L)
                val entry = zip.getEntry(name) ?: throw IllegalArgumentException("golden entry is missing: $name")
                require(!entry.isDirectory) { "golden entry is a directory: $name" }
                val actualHash = hashEntry(zip, name)
                require(actualHash == expectedHash) { "golden entry hash mismatch: $name" }
                val records = countRecords(zip, name)
                require(records == expectedRecords) {
                    "golden entry record count mismatch: $name expected=$expectedRecords actual=$records"
                }
                if (name.startsWith("engine/") && name.endsWith(".jsonl")) engineNames.add(name)
            }
            require(engineNames.isNotEmpty()) { "golden bundle has no engine JSONL" }
            return readMarketRecords(zip, engineNames.sorted(), manifest)
        }
    }

    private fun hashEntry(
        zip: ZipFile,
        name: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(zip.getInputStream(zip.getEntry(name)), digest).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (input.read(buffer) >= 0) Unit
        }
        return digest.digest().toHex()
    }

    private fun countRecords(
        zip: ZipFile,
        name: String,
    ): Long =
        zip.getInputStream(zip.getEntry(name)).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.count { it.isNotBlank() }.toLong()
        }
}
