package com.qkt.connectivity.transport

import com.qkt.common.Clock
import com.qkt.common.FixedClock
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TransportRecorderTest {
    private fun client(journal: TransportJournal) =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(TransportRecorder(journal) { 0L })
            .build()

    @Test
    fun `a transport failure is journalled with its error and still thrown`(
        @TempDir tmp: Path,
    ) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val journal = TransportJournal(tmp, "p", FixedClock(0L))
            val call = client(journal).newCall(Request.Builder().url(server.url("/orders?x=1")).build())

            assertThatThrownBy { call.execute() }.isInstanceOf(IOException::class.java)
            journal.close()

            val line = tmp.resolve("p/transport-1970-01-01.jsonl").readText()
            assertThat(line).contains("\"path\":\"/orders?x=1\"", "\"error\":").doesNotContain("responseCode")
        }
    }

    @Test
    fun `a journal that cannot capture never affects the response`(
        @TempDir tmp: Path,
    ) {
        val broken =
            object : Clock {
                override fun now(): Long = throw IllegalStateException("clock down")
            }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("ok"))
            val journal = TransportJournal(tmp, "p", broken)
            val response = client(journal).newCall(Request.Builder().url(server.url("/x")).build()).execute()

            assertThat(response.code).isEqualTo(201)
            assertThat(response.body!!.string()).isEqualTo("ok")
            journal.close()
            assertThat(tmp.resolve("p").exists()).isFalse
        }
    }
}
