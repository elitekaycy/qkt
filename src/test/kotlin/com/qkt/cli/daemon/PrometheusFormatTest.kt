package com.qkt.cli.daemon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrometheusFormatTest {
    @Test
    fun `counter with no labels emits help, type, and a single sample line`() {
        val out =
            PrometheusFormat()
                .counter(
                    "qkt_demo_total",
                    "Demo counter",
                    listOf(PrometheusFormat.Sample(value = "42")),
                ).toString()

        assertThat(out).contains("# HELP qkt_demo_total Demo counter")
        assertThat(out).contains("# TYPE qkt_demo_total counter")
        assertThat(out).contains("qkt_demo_total 42")
    }

    @Test
    fun `gauge with labels emits one sample per label-set`() {
        val out =
            PrometheusFormat()
                .gauge(
                    "qkt_strategy_state",
                    "Strategy state",
                    listOf(
                        PrometheusFormat.Sample(labels = mapOf("strategy" to "alpha"), value = "1"),
                        PrometheusFormat.Sample(labels = mapOf("strategy" to "beta"), value = "0"),
                    ),
                ).toString()

        assertThat(out).contains("# TYPE qkt_strategy_state gauge")
        assertThat(out).contains("""qkt_strategy_state{strategy="alpha"} 1""")
        assertThat(out).contains("""qkt_strategy_state{strategy="beta"} 0""")
    }

    @Test
    fun `label values escape backslash, quote, and newline`() {
        val out =
            PrometheusFormat()
                .gauge(
                    "qkt_escape",
                    "escape test",
                    listOf(PrometheusFormat.Sample(labels = mapOf("v" to "a\\b\"c\nd"), value = "1")),
                ).toString()

        assertThat(out).contains("""qkt_escape{v="a\\b\"c\nd"} 1""")
    }

    @Test
    fun `multiple labels join with commas in declared order`() {
        val out =
            PrometheusFormat()
                .counter(
                    "qkt_orders_total",
                    "orders",
                    listOf(
                        PrometheusFormat.Sample(
                            labels = linkedMapOf("strategy" to "alpha", "broker" to "EXNESS"),
                            value = "12",
                        ),
                    ),
                ).toString()

        assertThat(out).contains("""qkt_orders_total{strategy="alpha",broker="EXNESS"} 12""")
    }
}
