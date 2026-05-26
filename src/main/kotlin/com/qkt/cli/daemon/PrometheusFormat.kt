package com.qkt.cli.daemon

/**
 * Tiny builder for [Prometheus text exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/#text-based-format).
 *
 * Single-purpose helper for the daemon's `/metrics` endpoint (#79). Not a full library —
 * just counter, gauge, and summary in the shape qkt needs today. Labels are formatted
 * inline; values escape only the bare minimum (label values per the spec).
 */
internal class PrometheusFormat {
    private val sb = StringBuilder()

    /** Emit a `# HELP` then `# TYPE` block followed by samples. Counter form. */
    fun counter(
        name: String,
        help: String,
        samples: List<Sample>,
    ): PrometheusFormat = section(name, help, "counter", samples)

    /** Emit a gauge block. */
    fun gauge(
        name: String,
        help: String,
        samples: List<Sample>,
    ): PrometheusFormat = section(name, help, "gauge", samples)

    private fun section(
        name: String,
        help: String,
        type: String,
        samples: List<Sample>,
    ): PrometheusFormat {
        sb
            .append("# HELP ")
            .append(name)
            .append(' ')
            .append(help)
            .append('\n')
        sb
            .append("# TYPE ")
            .append(name)
            .append(' ')
            .append(type)
            .append('\n')
        for (s in samples) appendSample(name, s)
        return this
    }

    private fun appendSample(
        name: String,
        s: Sample,
    ) {
        sb.append(name)
        if (s.labels.isNotEmpty()) {
            sb.append('{')
            s.labels.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(',')
                sb
                    .append(k)
                    .append("=\"")
                    .append(escapeLabelValue(v))
                    .append('"')
            }
            sb.append('}')
        }
        sb.append(' ').append(s.value).append('\n')
    }

    private fun escapeLabelValue(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

    override fun toString(): String = sb.toString()

    /** One labelled sample for a metric series. */
    data class Sample(
        val labels: Map<String, String> = emptyMap(),
        val value: String,
    ) {
        constructor(labels: Map<String, String>, value: Long) : this(labels, value.toString())
        constructor(labels: Map<String, String>, value: Double) : this(labels, value.toString())
    }
}
