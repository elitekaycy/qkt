package com.qkt.trade.session

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What a session leaves behind and how the next one over the same state picks it up. */
class BotSessionRestartTest : IntentSessionFixture() {
    private val hooked = "BUY x SIZING 0.1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 } ON_STOP { SELL x SIZING 0.1 }"

    @Test
    fun `rebuilding from the journal recreates the intents' exit hooks`(
        @TempDir dir: Path,
    ) {
        val journal = IntentJournal(dir.resolve("intents.jsonl"))
        val first = session(listOf(2400, 2401, 2402), journal = journal)
        first.next(gold)
        first.submit("brain", intent(hooked))
        first.drain()
        val original = first.exitHookReferences("brain")

        val second = session(listOf(2400, 2401), journal = IntentJournal(dir.resolve("intents.jsonl")))
        val rebuilt = second.rebuildFrom(IntentJournal.read(dir.resolve("intents.jsonl")))

        assertThat(original).isNotEmpty()
        assertThat(rebuilt).isEqualTo(1)
        assertThat(second.exitHookReferences("brain")).isEqualTo(original)
        assertThat(second.submit("brain", intent("BUY x SIZING 0.1")).intentId).isEqualTo("int-2")
    }

    @Test
    fun `flatten closes every identity's position`() {
        val s = session(listOf(2400, 2401, 2402, 2403, 2404), identities = listOf("brain", "other"))
        s.next(gold)
        s.submit("brain", intent("BUY x SIZING 0.1"))
        s.submit("other", intent("SELL x SIZING 0.2"))
        s.next(gold)

        s.flattenAll()
        s.next(gold)

        assertThat(s.legs()).isEmpty()
        assertThat(
            s
                .finish()!!
                .finalPositions.values
                .filter { it.quantity.signum() != 0 },
        ).isEmpty()
    }
}
