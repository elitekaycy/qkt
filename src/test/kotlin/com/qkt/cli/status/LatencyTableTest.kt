package com.qkt.cli.status

import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatencyTableTest {
    private fun render(body: String) = captureCliRun { renderLatencyTable(body) }

    private fun row(line: String) = line.trim().split(Regex("\\s+"))

    @Test
    fun `each stage of a single inner strategy prints under the deployment name with scaled units`() {
        val run =
            render(
                """
                {"alpha":{"enabled":"true","strategies":{"alpha":{
                  "signal":{"count":12,"p50Nanos":512,"p95Nanos":1500,"p99Nanos":2500000,"maxNanos":3000000000},
                  "fill":{}
                }}}}
                """.trimIndent(),
            )

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        val lines = run.out.trim().lines()
        assertThat(row(lines[0])).containsExactly("STRATEGY", "STAGE", "COUNT", "p50", "p95", "p99", "MAX")
        assertThat(row(lines[1])).containsExactly("alpha", "signal", "12", "512ns", "1.5µs", "2.50ms", "3.00s")
        assertThat(row(lines[2])).containsExactly("alpha", "fill", "0", "0ns", "0ns", "0ns", "0ns")
    }

    @Test
    fun `a book with several inner strategies prefixes each with the deployment name`() {
        val run =
            render(
                """
                {"book":{"enabled":"true","strategies":{
                  "a":{"risk":{"count":1,"p50Nanos":1,"p95Nanos":1,"p99Nanos":1,"maxNanos":1}},
                  "b":{"risk":{"count":2,"p50Nanos":2,"p95Nanos":2,"p99Nanos":2,"maxNanos":2}}
                }}}
                """.trimIndent(),
            )

        assertThat(
            run.out
                .lines()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { row(it).first() },
        ).containsExactly("book/a", "book/b")
    }

    @Test
    fun `disabled tracking is named and an enabled entry without strategies prints nothing`() {
        val run = render("""{"off":{"enabled":"false"},"empty":{"enabled":"true"}}""")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        val lines = run.out.trim().lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[1]).startsWith("off").contains("(disabled — set QKT_LATENCY_TRACKING=1)")
    }

    @Test
    fun `an empty body says nothing is deployed and a malformed body is a user error`() {
        assertThat(render("{}").out.trim()).isEqualTo("(no strategies deployed)")

        val bad = render("not json")
        assertThat(bad.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(bad.err).contains("malformed /latency response")
    }
}
