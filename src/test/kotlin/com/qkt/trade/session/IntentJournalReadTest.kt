package com.qkt.trade.session

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Reading back `intents.jsonl`, including older and odd lines a replay still has to cope with. */
class IntentJournalReadTest {
    @TempDir
    lateinit var tmp: Path

    private fun journal(vararg lines: String): Path =
        tmp.resolve("intents.jsonl").also { Files.write(it, lines.toList()) }

    @Test
    fun `a missing journal reads as nothing`() {
        val absent = tmp.resolve("none.jsonl")

        assertThat(IntentJournal.read(absent)).isEmpty()
        assertThat(IntentJournal.filledAt(absent)).isEmpty()
        assertThat(IntentJournal.firedTickMs(absent)).isEmpty()
        assertThat(IntentJournal.finishPoint(absent)).isNull()
    }

    @Test
    fun `a journal path with no parent directory is accepted as is`() {
        val bare = IntentJournal(Path.of("intents.jsonl"))

        assertThat(bare.path.parent).isNull()
    }

    @Test
    fun `an intent's last fate is read back with a non text reason as none`() {
        val path =
            journal(
                """{"type":"intent","intentId":"int-1","identity":"a","simMs":5,"wallMs":6,"tickSeq":2,"source":"S"}""",
                "",
                """{"type":"fate","intentId":"int-1","identity":"a","state":"fired","reason":{"x":1}}""",
                """{"type":"fate","intentId":"int-1","identity":"a","state":"filled","compiled":true}""",
                """{"type":"intent","intentId":"int-2","identity":"b","simMs":7,"wallMs":8,"tickSeq":3,"source":"T"}""",
            )

        val read = IntentJournal.read(path)

        assertThat(read.map { it.intentId }).containsExactly("int-1", "int-2")
        assertThat(read[0].lastFate).isEqualTo(IntentFate("int-1", "a", "filled", compiled = true))
        assertThat(read[0].tickSeq).isEqualTo(2L)
        assertThat(read[1].lastFate).isNull()
    }

    @Test
    fun `filled times come from the first filled fate that carries a numeric time`() {
        val path =
            journal(
                """{"type":"fate","intentId":"int-1","state":"filled"}""",
                """{"type":"fate","intentId":"int-1","state":"filled","atMs":"soon"}""",
                """{"type":"note","intentId":"int-1","state":"filled","atMs":3}""",
                """{"type":"fate","intentId":"int-1","state":"filled","atMs":10}""",
                """{"type":"fate","intentId":"int-1","state":"filled","atMs":20}""",
                """{"type":"fate","intentId":"int-2","state":"fired","atMs":30}""",
            )

        assertThat(IntentJournal.filledAt(path)).containsExactlyEntriesOf(mapOf("int-1" to 10L))
    }

    @Test
    fun `the fired tick is the first numeric tick on a fate line`() {
        val path =
            journal(
                """{"type":"intent","intentId":"int-1","tickMs":1}""",
                """{"type":"fate","intentId":"int-1","tickMs":null}""",
                """{"type":"fate","intentId":"int-1","tickMs":"x"}""",
                """{"type":"fate","intentId":"int-1","tickMs":40}""",
                """{"type":"fate","intentId":"int-1","tickMs":50}""",
                """{"type":"fate","intentId":"int-2","state":"queued"}""",
            )

        assertThat(IntentJournal.firedTickMs(path)).containsExactlyEntriesOf(mapOf("int-1" to 40L))
    }

    @Test
    fun `the finish point is the last finish line, or none when the session never finished`() {
        val finished =
            journal(
                """{"type":"finish","simMs":100,"tickSeq":10}""",
                """{"type":"finish","simMs":200,"tickSeq":20}""",
            )
        assertThat(IntentJournal.finishPoint(finished)).isEqualTo(200L to 20L)

        Files.write(finished, listOf("""{"type":"intent","intentId":"int-1"}"""))
        assertThat(IntentJournal.finishPoint(finished)).isNull()
    }
}
