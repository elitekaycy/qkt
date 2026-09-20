package com.qkt.observe.insights

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

abstract class LiveSessionInsightsFixture {
    protected lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        // Every batch the sink sends gets a 200 ack.
        repeat(20) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }
}
