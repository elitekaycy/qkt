package com.qkt.cli.bot

import com.qkt.backtest.EventSlotData
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt bot universe --at` lists a moving set's members at an instant, from the event catalogs too. */
class BotUniverseAtTest {
    @TempDir
    lateinit var tmp: Path

    private fun universe(vararg argv: String): Pair<Int, String> {
        val data = tmp.resolve("data").also { if (!it.toFile().exists()) EventSlotData.seed(it) }
        val out = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(out))
                BotUniverseCommand(Args(arrayOf("universe") + argv + arrayOf("--data-root", data.toString()))).run()
            } finally {
                System.setOut(o)
                System.setErr(e)
            }
        return code to out.toString()
    }

    private val front = "KALSHI:KXS@front.* WHERE kind = event TOP 10 BY symbol ASC"

    @Test
    fun `a front ladder lists the contracts of the event open at the instant`() {
        val (code, out) = universe(front, "--at", "2024-03-04T10:30:00Z", "--json")
        assertThat(code).describedAs(out).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"count\":4").contains("KALSHI:KXS_H1_T1").doesNotContain("KXS_H2_T1")
        val (_, later) = universe(front, "--at", "2024-03-04T11:30:00Z")
        assertThat(later).contains("4 member(s)").contains("KALSHI:KXS_H2_T4").doesNotContain("KXS_H1_T1")
    }

    @Test
    fun `a set ranked on market figures, and a slot universe, are refused naming why`() {
        val (code, out) = universe("KALSHI:* WHERE volume > 1 TOP 5 BY symbol", "--at", "2024-03-04T10:30:00Z")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("only a running session sees")
        val (_, slots) = universe("KALSHI:*@front.p70 WHERE kind = event TOP 5 BY symbol", "--json")
        assertThat(slots).contains("names each family's slot").contains("KALSHI:<FAMILY>@front.*")
    }
}
