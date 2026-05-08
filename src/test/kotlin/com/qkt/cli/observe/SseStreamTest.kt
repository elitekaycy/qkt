package com.qkt.cli.observe

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SseStreamTest {
    @Test
    fun `SSE streams appended entries as event-data frames`() {
        val ring = EventRing()
        val server =
            ObservabilityServer(
                ring = ring,
                statusProvider = { error("not used") },
                running = { true },
                onStop = {},
                bind = "127.0.0.1",
                port = 0,
            )
        server.start()
        try {
            val received = java.util.concurrent.CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder().readTimeout(Duration.ofSeconds(2)).build()
            val req = Request.Builder().url("http://127.0.0.1:${server.boundPort}/events").build()

            val connected = java.util.concurrent.CountDownLatch(1)
            val future =
                CompletableFuture.runAsync {
                    runCatching {
                        client.newCall(req).execute().body!!.byteStream().bufferedReader().use { reader ->
                            var line = reader.readLine()
                            while (line != null && received.size < 5) {
                                if (line.isNotBlank()) {
                                    received.add(line)
                                    if (line.startsWith(":")) connected.countDown()
                                }
                                line = reader.readLine()
                            }
                        }
                    }
                }

            assertThat(connected.await(3, TimeUnit.SECONDS))
                .withFailMessage("SSE prelude never received")
                .isTrue()
            ring.append("trade", buildJsonObject { put("v", "x") })
            ring.append("signal", buildJsonObject { put("v", "y") })

            future.get(3, TimeUnit.SECONDS)
            assertThat(received.filter { it.startsWith("event:") }).hasSize(2)
            assertThat(received.filter { it.startsWith("data:") }).hasSize(2)
            assertThat(received.first { it.startsWith("event:") }).contains("trade")
            assertThat(received.last { it.startsWith("event:") }).contains("signal")
        } finally {
            server.close()
        }
    }
}
