package com.qkt.cli.observe

import java.math.BigDecimal
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityServerStatusTest : ObservabilityServerFixture() {
    private fun fixtureSnapshot(): StatusSnapshot =
        StatusSnapshot(
            strategy = "demo",
            version = 1,
            uptimeMs = 100L,
            startedAt = "2026-05-08T14:31:14Z",
            equity = BigDecimal("9997.66"),
            balance = BigDecimal("10000.00"),
            realized = BigDecimal("-2.34"),
            unrealized = BigDecimal("0.00"),
            positions = listOf(PositionDto("BTCUSDT", BigDecimal("0.001"), BigDecimal("68234.50"))),
            lastTrade = null,
        )

    @Test
    fun `status returns serialized snapshot as JSON`() {
        val s = server(statusProvider = { fixtureSnapshot() })
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/status").build()).execute()
            assertThat(resp.code).isEqualTo(200)
            assertThat(resp.header("Content-Type")).contains("application/json")
            val body = resp.body!!.string()
            assertThat(body).contains("\"strategy\":\"demo\"")
            assertThat(body).contains("\"equity\":9997.66")
            assertThat(body).contains("\"realized\":-2.34")
        } finally {
            s.close()
        }
    }

    @Test
    fun `status rejects POST with 405`() {
        val s = server(statusProvider = { fixtureSnapshot() })
        s.start()
        try {
            val req =
                Request
                    .Builder()
                    .url("http://127.0.0.1:${s.boundPort}/status")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            val resp = client.newCall(req).execute()
            assertThat(resp.code).isEqualTo(405)
        } finally {
            s.close()
        }
    }

    @Test
    fun `status returns 500 when provider throws`() {
        val s = server(statusProvider = { error("synthetic failure") })
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/status").build()).execute()
            assertThat(resp.code).isEqualTo(500)
        } finally {
            s.close()
        }
    }
}
