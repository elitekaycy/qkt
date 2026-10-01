package com.qkt.backtest.report

import com.qkt.backtest.StructureLog
import com.qkt.events.StructureClosed
import com.qkt.events.StructureOpened
import com.qkt.events.StructureOutcome
import com.qkt.strategy.StructureLegPosition
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `structures.csv`: one row per structure, closed or still live, with legs read from their entries. */
class StructureReportTest {
    private val short =
        StructureLegPosition(
            "DERIBIT:P81",
            BigDecimal.ONE,
            9L,
            BigDecimal("-0.1"),
            BigDecimal("646"),
            BigDecimal("-0.1"),
            BigDecimal.ZERO,
        )
    private val long =
        StructureLegPosition(
            "DERIBIT:P78",
            BigDecimal.ONE,
            9L,
            BigDecimal("0.1"),
            BigDecimal("219"),
            BigDecimal("0.1"),
            BigDecimal.ZERO,
        )

    @Test
    fun `a closed structure and a live one each get a row, unknowns left empty`() {
        val log = StructureLog()
        log.record(StructureOpened("s", "ps-1", "ps", listOf(short, long), BigDecimal("42.7"), timestamp = 100))
        log.record(
            StructureClosed(
                "s",
                "ps-1",
                "ps",
                listOf(short, long),
                StructureOutcome.CLOSED,
                BigDecimal("22.7"),
                timestamp = 200,
            ),
        )
        log.record(StructureOpened("s", "qs-1", "qs", listOf(long), BigDecimal("-21.9"), timestamp = 300))

        val csv = DerivativeReportFiles.structures(log.entries).toMap().getValue("structures.csv")

        assertThat(csv).isEqualTo(
            "openedAt,closedAt,strategy,structure,alias,outcome,legs,credit,realized\n" +
                "100,200,s,ps-1,ps,CLOSED,SELL 0.1 DERIBIT:P81 @ 646;BUY 0.1 DERIBIT:P78 @ 219,42.7,22.7\n" +
                "300,,s,qs-1,qs,,BUY 0.1 DERIBIT:P78 @ 219,-21.9,\n",
        )
    }

    @Test
    fun `a run without structures has no structures file`() {
        assertThat(DerivativeReportFiles.structures(StructureLog().entries)).isEmpty()
    }
}
