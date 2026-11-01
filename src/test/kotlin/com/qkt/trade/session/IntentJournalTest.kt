package com.qkt.trade.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A session's record: every intent in full with where it entered the replay, and what became of it. */
class IntentJournalTest : IntentSessionFixture() {
    @Test
    fun `every intent is journaled with its source and tick sequence`(
        @TempDir dir: Path,
    ) {
        val journal = dir.resolve("intents.jsonl")
        val s = session(listOf(2400, 2401, 2402, 2403), journal = IntentJournal(journal))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1"))
        s.next(gold)
        s.submit("brain", intent("CLOSE x"))
        s.drain()

        val lines = Files.readAllLines(journal)
        val submits = lines.filter { it.contains("\"type\":\"intent\"") }
        assertThat(submits).hasSize(2)
        assertThat(submits[0])
            .contains("\"intentId\":\"int-1\"")
            .contains("\"identity\":\"brain\"")
            .contains("BUY x SIZING 0.1")
            .contains("\"tickSeq\":3")
        assertThat(submits[1]).contains("CLOSE x").contains("\"tickSeq\":5")
        // Both intents filled (a fill can come back before its intent's `fired`; that adds the tick it fired on).
        val filled = lines.filter { it.contains("\"type\":\"fate\"") && it.contains("\"state\":\"filled\"") }
        assertThat(filled.map { it.substringAfter("\"intentId\":\"").substringBefore('"') }.distinct())
            .containsExactly("int-1", "int-2")
    }

    @Test
    fun `reads carry the bars served`(
        @TempDir dir: Path,
    ) {
        val reads = dir.resolve("reads.jsonl")
        val s = session(listOf(2400, 2401, 2402))
        val handlers = BotSessionHandlers(s, "USD", { null }, null, reads, null, com.qkt.common.SystemClock())

        handlers.next(gold)

        assertThat(
            Files.readString(reads),
        ).contains("\"verb\":\"next\"").contains("\"bars\":[{").contains("\"open\":2400")
    }

    @Test
    fun `finish lists working orders that could fill after it with nothing managing them`() {
        val s = session(listOf(2400, 2401, 2402))
        s.next(gold)
        s.submit(
            "brain",
            intent("BUY x SIZING 0.1 ORDER_TYPE = LIMIT AT 2300 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }"),
        )
        s.next(gold)
        val handlers = BotSessionHandlers(s, "USD", { null }, null, null, null, com.qkt.common.SystemClock())

        val reply = handlers.finish()

        assertThat(reply).contains("\"workingOrders\":[{\"intentId\":\"int-1\"").contains("\"note\":\"")
    }

    @Test
    fun `the session descriptor is readable by its owner only`(
        @TempDir dir: Path,
    ) {
        val path =
            BotSessionFiles.write(
                dir,
                BotSessionDescriptor(runId = "r", port = 1, token = "t", mode = "backtest", pid = 1),
            )

        val posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
        if (posix != null) {
            assertThat(posix.readAttributes().permissions())
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        } else {
            // Windows: only the owner and the running user may appear in the ACL.
            val acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView::class.java)!!
            val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            assertThat(acl.acl.map { it.principal() }).allMatch { it == acl.owner || it == user }
        }
    }
}
