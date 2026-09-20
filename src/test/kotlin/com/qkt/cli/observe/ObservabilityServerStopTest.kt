package com.qkt.cli.observe

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityServerStopTest : ObservabilityServerFixture() {
    @Test
    fun `stop POST returns 202 and invokes onStop with flatten false`() {
        val captured =
            java.util.concurrent.atomic
                .AtomicReference<Boolean?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val s =
            server(
                onStop = {
                    captured.set(it)
                    latch.countDown()
                },
            )
        s.start()
        try {
            val req =
                Request
                    .Builder()
                    .url("http://127.0.0.1:${s.boundPort}/stop")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            val resp = client.newCall(req).execute()
            assertThat(resp.code).isEqualTo(202)
            val body = resp.body!!.string()
            assertThat(body).contains("\"status\":\"accepted\"")
            assertThat(body).contains("\"action\":\"graceful_shutdown\"")
            assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            assertThat(captured.get()).isFalse()
        } finally {
            s.close()
        }
    }

    @Test
    fun `stop POST with flatten true invokes onStop with flatten true`() {
        val captured =
            java.util.concurrent.atomic
                .AtomicReference<Boolean?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        val s =
            server(
                onStop = {
                    captured.set(it)
                    latch.countDown()
                },
            )
        s.start()
        try {
            val req =
                Request
                    .Builder()
                    .url("http://127.0.0.1:${s.boundPort}/stop?flatten=true")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            val resp = client.newCall(req).execute()
            assertThat(resp.code).isEqualTo(202)
            assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            assertThat(captured.get()).isTrue()
        } finally {
            s.close()
        }
    }

    @Test
    fun `stop GET returns 405`() {
        val s = server(onStop = {})
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/stop").build()).execute()
            assertThat(resp.code).isEqualTo(405)
        } finally {
            s.close()
        }
    }
}
