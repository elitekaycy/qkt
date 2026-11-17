package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuditSummaryBranchesTest {
    private val dir: Path = createTempDirectory("audit")
    private val tick = """"tick":{"timestampMs":1,"price":"10.5"}"""
    private val candle =
        """"candle":{"startTimeMs":1,"endTimeMs":2,"open":"1","high":"2","low":"0.5","close":"1.5","volume":"9"}"""

    private fun rec(
        type: String,
        ts: Long,
        extra: String = "",
    ) = """{"ts":$ts,"eventType":"com.qkt.events.$type"${if (extra.isEmpty()) "" else ",$extra"}}"""

    private fun journal(
        vararg lines: String,
        name: String = "a.jsonl",
    ): Path = dir.resolve(name).also { it.writeText(lines.joinToString("\n")) }

    private fun refusal(vararg lines: String) =
        assertFailsWith<IllegalArgumentException> { scanAudit(listOf(journal(*lines))) }.message.orEmpty()

    @Test
    fun summarizesEveryEventKind() {
        val strat = """"strategyId":"s","alias":"a","broker":"b","timeframe":"1m","symbol":"X",$candle"""
        val file =
            journal(
                rec("TickEvent", 50, """"symbol":"X",$tick"""),
                "",
                rec("WarmupTickEvent", 10, """"symbol":"X",$tick"""),
                rec("CandleEvent", 20, """"symbol":"X",$candle"""),
                rec("StreamCandleEvent", 30, """"broker":"b","timeframe":"1m","symbol":"X",$candle"""),
                rec("StrategyCandleEvaluatedEvent", 40, strat),
                rec("BrokerEvent.OrderFilled", 60, """"orderId":"o1","fill":{"brokerOrderId":"b1"}"""),
                rec("BrokerEvent.OrderPartiallyFilled", 70, """"orderId":" ","fill":{"brokerOrderId":""}"""),
                rec("BrokerEvent.OrderFilled", 80, """"fill":"nope""""),
                rec("Other", 90),
                """{"ts":95}""",
            )
        val gz = dir.resolve("b.jsonl.gz")
        GZIPOutputStream(Files.newOutputStream(gz)).use {
            it.write(rec("TickEvent", 5, """"symbol":"X",$tick""").toByteArray())
        }
        val summary = scanAudit(listOf(file, gz))
        assertEquals(5L, summary.firstTimestampMs)
        assertEquals(95L, summary.lastTimestampMs)
        assertEquals(2L, summary.tickCount)
        assertEquals(1L, summary.warmupTickCount)
        assertEquals(1L, summary.candleCount)
        assertEquals(1L, summary.streamCandleCount)
        assertEquals(1L, summary.strategyCandleEvaluationCount)
        assertEquals(3L, summary.fillCount)
        assertEquals(setOf("o1"), summary.filledOrderIds)
        assertEquals(setOf("b1"), summary.filledBrokerOrderIds)
    }

    @Test
    fun emptyJournalIsRefused() {
        assertEquals("engine audit journal is empty", refusal(""))
        assertEquals(
            "engine audit journal is empty",
            assertFailsWith<IllegalArgumentException> {
                scanAudit(emptyList())
            }.message,
        )
    }

    @Test
    fun malformedRecordsAreRefusedWithPosition() {
        assertTrue(refusal("not json").startsWith("malformed JSONL at"))
        assertTrue(refusal("[1]").startsWith("malformed JSONL at"))
        assertTrue(refusal("""{"eventType":"x"}""").contains("missing numeric ts at"))
        assertTrue(refusal("""{"ts":"abc"}""").contains("missing numeric ts"))
    }

    @Test
    fun structuredFieldsAreRequired() {
        assertTrue("missing symbol" in refusal(rec("TickEvent", 1, tick)))
        assertTrue("missing structured tick" in refusal(rec("TickEvent", 1, """"symbol":"X"""")))
        assertTrue(
            "missing numeric timestampMs" in
                refusal(rec("TickEvent", 1, """"symbol":"X","tick":{"price":"1"}""")),
        )
        assertTrue(
            "missing decimal price" in
                refusal(rec("TickEvent", 1, """"symbol":"X","tick":{"timestampMs":1,"price":"zz"}""")),
        )
        assertTrue(
            "missing decimal price" in refusal(rec("WarmupTickEvent", 1, """"symbol":"X","tick":{"timestampMs":1}""")),
        )
        assertTrue("missing structured candle" in refusal(rec("CandleEvent", 1, """"symbol":"X"""")))
        assertTrue("missing numeric startTimeMs" in refusal(rec("CandleEvent", 1, """"symbol":"X","candle":{}""")))
        assertTrue(
            "missing numeric endTimeMs" in
                refusal(
                    rec("CandleEvent", 1, """"symbol":"X","candle":{"startTimeMs":1}"""),
                ),
        )
        assertTrue(
            "missing decimal open" in
                refusal(rec("CandleEvent", 1, """"symbol":"X","candle":{"startTimeMs":1,"endTimeMs":2}""")),
        )
        assertTrue("missing broker" in refusal(rec("StreamCandleEvent", 1, """"symbol":"X",$candle""")))
        assertTrue("missing timeframe" in refusal(rec("StreamCandleEvent", 1, """"broker":"b","symbol":"X",$candle""")))
        assertTrue("missing strategyId" in refusal(rec("StrategyCandleEvaluatedEvent", 1)))
        assertTrue("missing alias" in refusal(rec("StrategyCandleEvaluatedEvent", 1, """"strategyId":"s"""")))
    }
}
