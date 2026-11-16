package com.qkt.marketdata.store.macro

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PolicyRateArtifactReaderSizeTest {
    @Test
    fun `a streamed download past the size limit is refused though it declared no length`() {
        MockWebServer().use { server ->
            val body = Buffer().write(ByteArray(10 * 1024 * 1024 + 1))
            server.enqueue(MockResponse().setChunkedBody(body, 64 * 1024))
            server.start()

            assertThatThrownBy {
                PolicyRateArtifactReader(
                    OkHttpClient(),
                ).read(server.url("/a.xlsx").toString(), "RBNZ")
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("RBNZ policy-rate artifact exceeds")
        }
    }
}
