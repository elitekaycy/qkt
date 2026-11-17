package com.qkt.cli.soak

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoldenEvidenceBranchesTest {
    private val dir: Path = createTempDirectory("golden")

    private fun hex(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun counts(missing: String? = null) =
        listOf("ticks", "fills", "gatewayExchanges", "linkedPlacements")
            .filter { it != missing }
            .joinToString(",", "{", "}") { "\"$it\":3" }

    private fun manifest(
        kind: String = "MT5_GOLDEN_CAPTURE",
        session: String = "s",
        counts: String? = counts(),
        entries: String? = "[]",
    ): String {
        val parts = mutableListOf("\"kind\":\"$kind\"", "\"session\":\"$session\"")
        counts?.let { parts += "\"counts\":$it" }
        entries?.let { parts += "\"entries\":$it" }
        return parts.joinToString(",", "{", "}")
    }

    private fun zip(
        manifest: String?,
        files: Map<String, String> = emptyMap(),
    ): Path {
        val path = Files.createTempFile(dir, "bundle", ".zip")
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            if (manifest != null) put(out, "manifest.json", manifest)
            files.forEach { (name, body) -> put(out, name, body) }
        }
        return path
    }

    private fun put(
        out: ZipOutputStream,
        name: String,
        body: String,
    ) {
        out.putNextEntry(ZipEntry(name))
        out.write(body.toByteArray())
        out.closeEntry()
    }

    private fun entries(vararg files: Pair<String, String>) =
        files.joinToString(",", "[", "]") { (name, body) -> """{"path":"$name","sha256":"${hex(body)}"}""" }

    private fun refusal(path: Path) = assertFailsWith<IllegalArgumentException> { inspectGolden(path, "s") }.message

    @Test
    fun manifestProblemsAreRefused() {
        assertEquals("golden bundle has no manifest.json", refusal(zip(null)))
        assertEquals("unsupported golden bundle kind", refusal(zip(manifest(kind = "OTHER"))))
        assertEquals("golden bundle session does not match 's'", refusal(zip(manifest(session = "x"))))
        assertEquals("golden bundle has no counts", refusal(zip(manifest(counts = null))))
        assertEquals("golden bundle has no fills", refusal(zip(manifest(counts = counts("fills")))))
        assertEquals("golden bundle has no entries", refusal(zip(manifest(entries = null))))
        assertEquals(true, refusal(zip("[1]"))!!.startsWith("malformed JSON"))
    }

    @Test
    fun entryProblemsAreRefused() {
        assertEquals("golden entry path missing", refusal(zip(manifest(entries = """[{"sha256":"x"}]"""))))
        assertEquals("golden entry SHA-256 missing", refusal(zip(manifest(entries = """[{"path":"a"}]"""))))
        assertEquals(
            "golden entry missing: a.txt",
            refusal(zip(manifest(entries = """[{"path":"a.txt","sha256":"x"}]"""))),
        )
        assertEquals(
            "golden entry SHA-256 mismatch: a.txt",
            refusal(zip(manifest(entries = """[{"path":"a.txt","sha256":"x"}]"""), mapOf("a.txt" to "body"))),
        )
    }

    @Test
    fun cleanBundleWithoutGatewayLogsHasNoUnknownOutcomes() {
        val body = "plain"
        val path = zip(manifest(entries = entries("a.txt" to body)), mapOf("a.txt" to body))
        assertEquals(0L, inspectGolden(path, "s").unknownOutcomePlacements)
    }

    @Test
    fun gatewayExchangesCountUnknownMutations() {
        fun line(
            method: String,
            path: String,
            code: String? = "200",
            body: String? = """{\"result\":{\"retcode\":10009}}""",
        ): String {
            val parts = mutableListOf("\"method\":\"$method\"", "\"path\":\"$path\"")
            code?.let { parts += "\"responseCode\":$it" }
            body?.let { parts += "\"responseBody\":\"$it\"" }
            return parts.joinToString(",", "{", "}")
        }
        val lines =
            listOf(
                line("POST", "/order"),
                line("POST", "/order", code = "409"),
                line("POST", "/close_position?x=1", code = "503"),
                line("POST", "/modify_sl_tp", code = null),
                line("POST", "/cancel_order", code = "404"),
                line("POST", "/order", body = null),
                line("POST", "/order", body = "not json"),
                line("POST", "/order", body = """{\"result\":{}}"""),
                line("POST", "/position_close_partial", body = "{}"),
                line("GET", "/order", code = "500"),
                line("POST", "/other", code = "500"),
                "",
            ).joinToString("\n")
        val name = "gateway/ex.jsonl"
        val path = zip(manifest(entries = entries(name to lines)), mapOf(name to lines))
        assertEquals(6L, inspectGolden(path, "s").unknownOutcomePlacements)
    }
}
