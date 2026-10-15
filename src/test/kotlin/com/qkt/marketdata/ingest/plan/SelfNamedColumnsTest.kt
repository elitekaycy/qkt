package com.qkt.marketdata.ingest.plan

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SelfNamedColumnsTest {
    private val bars = ImportDescriptor(kind = IngestKind.BARS)

    @Test
    fun `columns named after qkt's fields map themselves, any case`() {
        val d = SelfNamedColumns.fill(bars, listOf("Timestamp", "Open", "HIGH", "low", "close", "vol"))

        assertThat(d.fields).isEqualTo(mapOf("open" to "Open", "high" to "HIGH", "low" to "low", "close" to "close"))
        assertThat(d.time).containsExactly("Timestamp")
    }

    @Test
    fun `stated mappings win and a split date and time stays for the user to say`() {
        val stated = bars.copy(fields = mapOf("close" to "last"))

        val d = SelfNamedColumns.fill(stated, listOf("date", "time", "open", "close", "last"))

        assertThat(d.fields).containsEntry("close", "last").containsEntry("open", "open")
        assertThat(d.time).isEmpty()
    }

    @Test
    fun `nothing is mapped without a kind`() {
        assertThat(SelfNamedColumns.fill(ImportDescriptor(), listOf("time", "open"))).isEqualTo(ImportDescriptor())
    }
}
