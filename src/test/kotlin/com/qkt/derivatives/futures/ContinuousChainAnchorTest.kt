package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.ListedContract
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollRecord
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Crude's 2020 super-contango drives a forward panama series below zero from any start (qkt#1355).
 * A root that declares `roll.anchor` keeps that contract at its raw prices and shifts every other
 * contract onto it, so the series stays positive and remains tradeable.
 */
class ContinuousChainAnchorTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun root(
        dir: Path,
        roll: String,
    ): FuturesRoot {
        val file = dir.resolve("instruments.yaml")
        Files.writeString(
            file,
            "futures:\n  - { root: CME:CL, currency: USD, multiplier: 1000, tickSize: 0.01, volumeStep: 1, " +
                "volumeMin: 1, roll: { daysBeforeExpiry: 7, atUtc: '00:00', $roll } }\n",
        )
        return FuturesRootsFile.load(file).single()
    }

    private val catalog =
        ContractCatalog(
            "CME:CL",
            listOf(
                ListedContract("CLJ20", ms("2020-03-20T21:00:00Z")),
                ListedContract("CLK20", ms("2020-04-21T21:00:00Z")),
                ListedContract("CLM20", ms("2020-05-19T21:00:00Z")),
                ListedContract("CLN20", ms("2020-06-22T21:00:00Z")),
                ListedContract("CLQ20", ms("2020-07-21T21:00:00Z")),
            ),
        )

    // Steep contango: the forward shift reaches -16 by CLM20, which traded near 10 in April 2020.
    private val rolls =
        listOf(
            RollRecord(ms("2020-03-13T00:00:00Z"), "CLJ20", "CLK20", "25", "33"),
            RollRecord(ms("2020-04-14T00:00:00Z"), "CLK20", "CLM20", "20", "28"),
            RollRecord(ms("2020-05-12T00:00:00Z"), "CLM20", "CLN20", "25", "26"),
        )

    private fun chain(
        root: FuturesRoot,
        records: List<RollRecord> = rolls,
    ) = ContinuousChain(root, catalog, RollHistory("CME:CL", "7d@00:00", records), ContinuousSelector.FRONT)

    private fun ContinuousChain.continuous(
        contract: String,
        raw: String,
    ): BigDecimal = spaceFor(requireNotNull(indexOf("CME:$contract"))).toContinuous(BigDecimal(raw))

    @Test
    fun `an anchored panama series keeps the anchor raw and the 2020 lows positive`(
        @TempDir dir: Path,
    ) {
        val anchored = chain(root(dir, "adjust: panama, anchor: CLN20"))
        assertThat(anchored.continuous("CLN20", "26")).isEqualByComparingTo("26")
        assertThat(anchored.continuous("CLM20", "10")).isEqualByComparingTo("11")
        assertThat(anchored.continuous("CLJ20", "25")).isEqualByComparingTo("42")
        // Distances are unchanged, so each roll is still seamless.
        assertThat(anchored.continuous("CLM20", "25")).isEqualByComparingTo(anchored.continuous("CLN20", "26"))
        assertThat(anchored.continuous("CLJ20", "25")).isEqualByComparingTo(anchored.continuous("CLK20", "33"))
    }

    @Test
    fun `without an anchor the forward series is refused below zero`(
        @TempDir dir: Path,
    ) {
        val forward = chain(root(dir, "adjust: panama"))
        assertThatThrownBy { forward.continuous("CLM20", "10") }.hasMessageContaining("roll.anchor")
    }

    @Test
    fun `a roll appended after the anchor leaves every mapped contract where it was`(
        @TempDir dir: Path,
    ) {
        val root = root(dir, "adjust: panama, anchor: CLN20")
        val longer = chain(root, rolls + RollRecord(ms("2020-06-15T00:00:00Z"), "CLN20", "CLQ20", "38", "38.5"))
        assertThat(longer.continuous("CLM20", "10")).isEqualByComparingTo(chain(root).continuous("CLM20", "10"))
        assertThat(longer.continuous("CLQ20", "38.5")).isEqualByComparingTo("38")
    }

    @Test
    fun `an anchored ratio series keeps the anchor raw`(
        @TempDir dir: Path,
    ) {
        assertThat(
            chain(root(dir, "adjust: ratio, anchor: CLM20")).continuous("CLM20", "10"),
        ).isEqualByComparingTo("10")
    }

    @Test
    fun `an anchor outside the measured history is refused`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { chain(root(dir, "adjust: panama, anchor: CLQ20")) }
            .hasMessageContaining("CLQ20")
            .hasMessageContaining("measured")
        assertThatThrownBy { chain(root(dir, "adjust: panama, anchor: CLZ20")) }
            .hasMessageContaining("CLZ20")
    }
}
