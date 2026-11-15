package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.instrument.EventCatalogStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt data import --kind events`: any described file in, the family's event catalog out, nothing guessed. */
class EventImportTest {
    @TempDir
    lateinit var dir: Path

    private val root get() = dir.resolve("root")

    private fun run(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (savedOut, savedErr) = System.out to System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        val code =
            try {
                DataCommand(Args(arrayOf("data", "import", *argv, "--data-root", root.toString()))).run()
            } finally {
                System.setOut(savedOut)
                System.setErr(savedErr)
            }
        return Triple(code, out.toString(), err.toString())
    }

    private fun file(
        name: String,
        text: String,
    ): String = dir.resolve(name).also { Files.writeString(it, text) }.toString()

    private fun catalog() = requireNotNull(EventCatalogStore(root).read("KALSHI:KXT"))

    private val ownHeader =
        "code,event,strike_type,floor,cap,open,close,expiry,occurs,occasion,can_close_early,exclusive,frequency," +
            "title,taker_fee,maker_fee,fee_rounding,outcome\n"

    @Test
    fun `qkt's own columns import with no map`() {
        val f =
            file(
                "own.csv",
                ownHeader +
                    "KXT-E1-T1,KXT-E1,between,100,200,2024-03-04T08:00:00Z,2024-03-04T12:00:00Z," +
                    "1709553600000,1709550000000,OCC,yes,true,1d,\"100 to 200\",0.07,0.0175,0.01,no\n",
            )
        val (code, out, err) = run(f, "--as", "KALSHI:KXT", "--kind", "events")

        assertThat(code).describedAs(err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("KALSHI:KXT: 1 contract(s) read; merged into").contains("(1 resolved)")
        val row = catalog().contracts.single()
        assertThat(row.predicate.type).isEqualTo("between")
        assertThat(row.predicate.cap).isEqualTo("200")
        assertThat(row.openAtMs).isEqualTo(1_709_539_200_000L)
        assertThat(row.expiryMs).isEqualTo(1_709_553_600_000L)
        assertThat(row.occursAtMs).isEqualTo(1_709_550_000_000L)
        assertThat(row.canCloseEarly).isTrue()
        assertThat(row.exclusive).isTrue()
        assertThat(row.title).isEqualTo("100 to 200")
        assertThat(row.fee?.maker).isEqualTo("0.0175")
        assertThat(row.outcome).isEqualTo("0")
    }

    @Test
    fun `a described file maps its columns and a later import fills the outcome`() {
        val header = "ticker;event_ticker;strike_type;floor_strike;open_time;close_time;expiration_time;result\n"
        val listed =
            file(
                "listed.csv",
                header +
                    "KXT-E1-T1;KXT-E1;GREATER;100;2024-03-04T08:00:00Z;2024-03-04T12:00:00Z;2024-03-04T12:00:00Z;\n" +
                    "KXT-E1-T2;KXT-E1;greater;200;2024-03-04T08:00:00Z;2024-03-04T12:00:00Z;2024-03-04T12:00:00Z;\n",
            )
        val map =
            "code=ticker,event=event_ticker,floor=floor_strike,open=open_time,close=close_time," +
                "expiry=expiration_time,outcome=result"
        val args = arrayOf("--as", "KALSHI:KXT", "--kind", "events", "--delimiter", ";", "--map", map)

        assertThat(run(listed, *args).first).isEqualTo(ExitCodes.SUCCESS)
        assertThat(catalog().contracts.map { it.outcome }).containsExactly(null, null)

        val settled = file("settled.csv", header + listed.let { Files.readAllLines(Path.of(it))[1] } + "yes\n")
        val (code, out, _) = run(settled, *args, "--json")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("\"read\":1,\"stored\":2,\"resolved\":1").contains("\"dryRun\":false")
        assertThat(catalog().contracts.map { it.outcome }).containsExactly("1", null)
        assertThat(catalog().contracts.first().fee).isNull()
    }

    @Test
    fun `a dry run reports and writes nothing`() {
        val f = file("own.csv", ownHeader + "KXT-E1-T1,KXT-E1,none,,,1,2,3,,,,,,,,,,\n")
        val (code, out, _) = run(f, "--as", "KALSHI:KXT", "--kind", "events", "--dry-run")

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("would merge into")
        assertThat(EventCatalogStore(root).read("KALSHI:KXT")).isNull()
    }

    @Test
    fun `a contradicting outcome is refused and nothing is written`() {
        val f = file("a.csv", ownHeader + "KXT-E1-T1,KXT-E1,none,,,1,2,3,,,,,,,,,,1\n")
        run(f, "--as", "KALSHI:KXT", "--kind", "events")
        val g = file("b.csv", ownHeader + "KXT-E1-T1,KXT-E1,none,,,1,2,3,,,,,,,,,,0\n")

        val (code, _, err) = run(g, "--as", "KALSHI:KXT", "--kind", "events")

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err).contains("already resolved").contains("Nothing was written")
        assertThat(catalog().contracts.single().outcome).isEqualTo("1")
    }

    @Test
    fun `a row that cannot be read is refused naming its line`() {
        val cases =
            mapOf(
                "KXT-E1-T1,KXT-E1,none,,,soon,2,3,,,,,,,,,,\n" to "line 2: 'soon' is neither",
                "KXT-E1-T1,KXT-E1,none,,,1,2,3,,,maybe,,,,,,,\n" to "'maybe' is not true or false",
                "KXT-E1-T1,KXT-E1,none,,,1,2,3,,,,,,,abc,,0.01,\n" to "'abc'",
                "KXT-E1-T1,KXT-E1,none,,,1,2,3,,,,,,,0.07,,,\n" to "no fee_rounding",
                ",KXT-E1,none,,,1,2,3,,,,,,,,,,\n" to "no code",
                "KXT-E1-T1,KXT-E1,above,,,1,2,3,,,,,,,,,,\n" to "strike type 'above'",
            )
        for ((row, message) in cases) {
            val (code, _, err) = run(file("bad.csv", ownHeader + row), "--as", "KALSHI:KXT", "--kind", "events")
            assertThat(code).describedAs(row).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(err).describedAs(row).contains(message)
        }
    }

    @Test
    fun `bad arguments and files are refused before anything is read`() {
        val f = file("own.csv", ownHeader)
        assertThat(run(f, "--kind", "events").first).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run(f, "--as", "KXT", "--kind", "events").first).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run(f, "--as", "KALSHI:KXT", "--kind", "events", "--map", "nope=x").third)
            .contains("--map field 'nope'")
        assertThat(run(f, "--as", "KALSHI:KXT", "--kind", "events", "--map", "code").third)
            .contains("--map takes field=column")
        assertThat(run(file("empty.csv", ""), "--as", "KALSHI:KXT", "--kind", "events").third).contains("is empty")
        assertThat(run(file("h.csv", "code,event\n"), "--as", "KALSHI:KXT", "--kind", "events").third)
            .contains("no 'strike_type' column")
    }
}
