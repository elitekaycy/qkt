package com.qkt.connector.bybit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitExecutionKindTest {
    private fun kind(json: String) = BybitExecutionKind.of(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `a trade is a fill, funding is funding, a liquidation is neither, and a record naming no type is a fill`() {
        assertThat(kind("""{"execType":"Trade"}""")).isEqualTo(BybitExecutionKind.FILL)
        assertThat(kind("""{"execType":"Funding"}""")).isEqualTo(BybitExecutionKind.FUNDING)
        assertThat(kind("""{"execType":"BustTrade"}""")).isEqualTo(BybitExecutionKind.OTHER)
        assertThat(kind("""{"execId":"e1"}""")).isEqualTo(BybitExecutionKind.FILL)
    }
}
