package com.qkt.notify

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelegramClientGetUpdatesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TelegramClient

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        client =
            TelegramClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                botToken = "TOKEN",
                chatId = "CHAT",
                http = OkHttpClient.Builder().build(),
            )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `200 with two updates returns Received with both TelegramUpdates`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"ok":true,"result":[
                    {"update_id":1,"message":{"message_id":10,"chat":{"id":111,"type":"private"},"text":"/halt"}},
                    {"update_id":2,"message":{"message_id":11,"chat":{"id":111,"type":"private"}}}
                ]}""",
            ),
        )

        val result = client.getUpdates(offset = 0L, timeoutSeconds = 25)

        assertThat(result).isInstanceOf(UpdatesOutcome.Received::class.java)
        val received = result as UpdatesOutcome.Received
        assertThat(received.updates).hasSize(2)

        val first = received.updates[0]
        assertThat(first.updateId).isEqualTo(1L)
        assertThat(first.chatId).isEqualTo(111L)
        assertThat(first.text).isEqualTo("/halt")

        val second = received.updates[1]
        assertThat(second.updateId).isEqualTo(2L)
        assertThat(second.chatId).isEqualTo(111L)
        assertThat(second.text).isNull()
    }

    @Test
    fun `GET request path and query contain getUpdates, offset, and timeout`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"result":[]}"""))

        client.getUpdates(offset = 0L, timeoutSeconds = 25)

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.path).contains("getUpdates")
        assertThat(req.path).contains("offset=0")
        assertThat(req.path).contains("timeout=25")
    }

    @Test
    fun `200 with empty result returns Received with empty list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"result":[]}"""))

        val result = client.getUpdates(offset = 0L, timeoutSeconds = 25)

        assertThat(result).isEqualTo(UpdatesOutcome.Received(emptyList()))
    }

    @Test
    fun `200 with ok false returns Failed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":false,"description":"x"}"""))

        val result = client.getUpdates(offset = 0L, timeoutSeconds = 25)

        assertThat(result).isEqualTo(UpdatesOutcome.Failed)
    }

    @Test
    fun `500 returns Failed`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.getUpdates(offset = 0L, timeoutSeconds = 25)

        assertThat(result).isEqualTo(UpdatesOutcome.Failed)
    }

    @Test
    fun `200 with malformed body returns Failed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        val result = client.getUpdates(offset = 0L, timeoutSeconds = 25)

        assertThat(result).isEqualTo(UpdatesOutcome.Failed)
    }
}
