package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenBundleReaderRefusalsTest {
    @TempDir
    lateinit var tmp: Path

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun bundle(files: Map<String, String>): Path {
        val zip = tmp.resolve("bundle-${files.hashCode()}.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            files.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    private fun manifest(
        entries: String,
        version: Int = 2,
        kind: String = "MT5_GOLDEN_CAPTURE",
    ) = """{"schemaVersion":$version,"kind":"$kind","entries":$entries}"""

    private fun entry(
        path: String,
        records: Int,
        body: String,
    ) = """{"path":"$path","records":$records,"sha256":"${sha(body)}"}"""

    private fun refused(
        files: Map<String, String>,
        message: String,
    ) = assertThatThrownBy { GoldenBundleReader(bundle(files)).read() }.hasMessage(message)

    @Test
    fun `a bundle without a manifest of the right version and kind is refused`() {
        refused(mapOf("engine/a.jsonl" to ""), "golden bundle has no manifest.json")
        refused(mapOf("manifest.json" to manifest("[]", version = 1)), "unsupported golden schemaVersion")
        refused(mapOf("manifest.json" to manifest("[]", kind = "OTHER")), "unsupported golden bundle kind")
        refused(
            mapOf("manifest.json" to """{"schemaVersion":2,"kind":"MT5_GOLDEN_CAPTURE"}"""),
            "golden bundle has no entries",
        )
    }

    @Test
    fun `entries outside the known roots or escaping them are refused`() {
        refused(
            mapOf("manifest.json" to manifest("[${entry("secrets/a.jsonl", 0, "")}]")),
            "unsupported golden entry path: secrets/a.jsonl",
        )
        refused(
            mapOf("manifest.json" to manifest("[${entry("engine/../a.jsonl", 0, "")}]")),
            "unsafe golden entry path: engine/../a.jsonl",
        )
    }

    @Test
    fun `an entry whose record count differs from the manifest is refused`() {
        val body = "{\"a\":1}\n{\"a\":2}\n"

        refused(
            mapOf("manifest.json" to manifest("[${entry("engine/a.jsonl", 3, body)}]"), "engine/a.jsonl" to body),
            "golden entry record count mismatch: engine/a.jsonl expected=3 actual=2",
        )
    }
}
