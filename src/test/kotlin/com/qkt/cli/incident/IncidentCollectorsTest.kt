package com.qkt.cli.incident

import com.qkt.cli.daemon.StateDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The incident collectors against a state directory with missing, unfiltered and oversized sources. */
class IncidentCollectorsTest {
    @TempDir
    lateinit var tmp: Path

    private val bytes = ByteArrayOutputStream()

    private fun stateDir() = StateDir.resolve(tmp.toString())

    /** Runs [block] against a fresh bundle and returns it with the zip's entries by name. */
    private fun bundle(
        maxFileBytes: Long = 1024,
        block: (IncidentBundle) -> Unit,
    ): Pair<IncidentBundle, Map<String, String>> {
        val zip = ZipOutputStream(bytes)
        val bundle = IncidentBundle(zip, maxFileBytes)
        block(bundle)
        zip.close()
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return bundle to entries
    }

    private fun journal(
        strategy: String,
        text: String,
    ) {
        val dir = Files.createDirectories(tmp.resolve("state/journal/$strategy"))
        Files.writeString(dir.resolve("journal-2026-06-26.jsonl"), text)
    }

    @Test
    fun `missing journal, logs and state directories and a missing input are warned about, not collected`() {
        val state = stateDir()
        Files.delete(state.logsDir)
        val missingInput = tmp.resolve("gone.yaml")

        val (bundle, entries) =
            bundle {
                collectJournal(it, state, null, null, null)
                collectLogs(it, state, null)
                collectState(it, state, null)
                collectInput(it, "inputs/config.yaml", missingInput)
                collectInput(it, "inputs/strategy.qkt", null)
            }

        assertThat(bundle.warnings).containsExactly(
            "journal directory not found: ${state.stateRoot.resolve("journal")}",
            "logs directory not found: ${state.logsDir}",
            "state directory not found: ${state.stateRoot}",
            "input not found: $missingInput",
        )
        assertThat(bundle.included).isEmpty()
        assertThat(entries).isEmpty()
    }

    @Test
    fun `without a strategy every strategy's journal is sliced and lines without a timestamp are kept`() {
        journal("alpha", "{\"ts\":100,\"kind\":\"submit\"}\n{\"kind\":\"note\"}\n")
        journal("beta", "{\"ts\": 200,\"kind\":\"filled\"}\n")
        Files.writeString(tmp.resolve("state/journal/stray.txt"), "not a strategy directory")
        Files.writeString(tmp.resolve("state/journal/alpha/notes.txt"), "not a journal file")

        val (bundle, entries) = bundle { collectJournal(it, stateDir(), null, null, null) }

        assertThat(bundle.included).containsExactly(
            "journal-slice/alpha/journal-2026-06-26.jsonl",
            "journal-slice/beta/journal-2026-06-26.jsonl",
        )
        assertThat(entries["journal-slice/alpha/journal-2026-06-26.jsonl"])
            .isEqualTo("{\"ts\":100,\"kind\":\"submit\"}\n{\"kind\":\"note\"}\n")
        assertThat(entries["journal-slice/beta/journal-2026-06-26.jsonl"]).contains("\"kind\":\"filled\"")
    }

    @Test
    fun `a window drops untimed lines and lines outside it, and writes no entry when nothing is left`() {
        journal("alpha", "{\"ts\":100}\n{\"ts\":150}\n{\"ts\":200}\n{\"kind\":\"note\"}\n{\"ts\":\"x\"}\n")
        journal("beta", "{\"ts\":900}\n")

        val (bundle, entries) = bundle { collectJournal(it, stateDir(), null, 150, 200) }

        assertThat(bundle.included).containsExactly("journal-slice/alpha/journal-2026-06-26.jsonl")
        assertThat(entries.values.single()).isEqualTo("{\"ts\":150}\n")
    }

    @Test
    fun `a named strategy without a journal directory collects nothing`() {
        journal("alpha", "{\"ts\":100}\n")

        val (bundle, entries) = bundle { collectJournal(it, stateDir(), "ghost", null, null) }

        assertThat(bundle.included).isEmpty()
        assertThat(bundle.warnings).isEmpty()
        assertThat(entries).isEmpty()
    }

    @Test
    fun `without a strategy every log and every non-journal state file is collected`() {
        val state = stateDir()
        Files.writeString(state.logsDir.resolve("alpha.log"), "alpha line\n")
        Files.writeString(state.logsDir.resolve("daemon.log"), "daemon line\n")
        journal("alpha", "{\"ts\":100}\n")
        Files.createDirectories(state.stateRoot.resolve("alpha"))
        Files.writeString(state.stateRoot.resolve("alpha/pnl.json"), "{}")
        Files.writeString(state.stateRoot.resolve("book state.json"), "[]")

        val (bundle, entries) =
            bundle {
                collectLogs(it, state, null)
                collectState(it, state, null)
            }

        assertThat(bundle.included)
            .containsExactly("logs/alpha.log", "logs/daemon.log", "state/alpha/pnl.json", "state/book_state.json")
        assertThat(entries["logs/daemon.log"]).isEqualTo("daemon line\n")
    }

    @Test
    fun `a named strategy collects only its own log and state and skips a log it never wrote`() {
        val state = stateDir()
        Files.writeString(state.logsDir.resolve("beta.log"), "beta line\n")
        Files.createDirectories(state.stateRoot.resolve("alpha"))
        Files.createDirectories(state.stateRoot.resolve("beta"))
        Files.writeString(state.stateRoot.resolve("alpha/pnl.json"), "{}")
        Files.writeString(state.stateRoot.resolve("beta/pnl.json"), "{}")

        val (bundle, _) =
            bundle {
                collectLogs(it, state, "alpha")
                collectState(it, state, "alpha")
            }

        assertThat(bundle.included).containsExactly("state/alpha/pnl.json")
    }

    @Test
    fun `a file over the cap keeps only its last bytes and says so`() {
        val input = tmp.resolve("big.log")
        Files.writeString(input, "0123456789")

        val (bundle, entries) = bundle(maxFileBytes = 4) { collectInput(it, "inputs/big.log", input) }

        assertThat(entries).containsEntry("inputs/big.log", "6789")
        assertThat(bundle.warnings).containsExactly("truncated inputs/big.log from 10 to last 4 bytes")
        assertThat(bundle.included).containsExactly("inputs/big.log")
    }

    @Test
    fun `zip paths keep safe characters and replace the rest per segment`() {
        assertThat(sanitizePath("book a\\child:1/journal-2026.jsonl")).isEqualTo("book_a/child_1/journal-2026.jsonl")
    }
}
