package com.qkt.notify

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class TelegramClientEdgeTest {
    private val server = MockWebServer().also { it.start() }

    private val client =
        TelegramClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            botToken = "TOKEN",
            chatId = "CHAT",
            http =
                OkHttpClient
                    .Builder()
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .build(),
        )

    @AfterEach
    fun teardown() = server.shutdown()

    @Test
    fun `a status outside the known ranges is treated as transient`() {
        server.enqueue(MockResponse().setResponseCode(302))

        assertThat(client.send("x")).isEqualTo(TelegramClient.Outcome.TransientError(302))
    }

    @Test
    fun `a rate limit without retry after waits one second`() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertThat(client.send("x")).isEqualTo(TelegramClient.Outcome.RateLimited(retryAfterMs = 1_000L))
    }

    @Test
    fun `a dropped connection is a network error for sends and a failure for polls`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThat(client.send("x")).isInstanceOf(TelegramClient.Outcome.NetworkError::class.java)
        assertThat(client.getUpdates(offset = 0L, timeoutSeconds = 1)).isEqualTo(UpdatesOutcome.Failed)
    }

    @Test
    fun `an update without an id fails the whole poll`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":[{"message":{"text":"hi"}}]}"""))

        assertThat(client.getUpdates(offset = 0L, timeoutSeconds = 1)).isEqualTo(UpdatesOutcome.Failed)
    }

    @Test
    fun `an update without a message or result is still received`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":[{"update_id":9}]}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        assertThat(client.getUpdates(offset = 0L, timeoutSeconds = 1))
            .isEqualTo(UpdatesOutcome.Received(listOf(TelegramUpdate(updateId = 9L, chatId = null, text = null))))
        assertThat(client.getUpdates(offset = 10L, timeoutSeconds = 1)).isEqualTo(UpdatesOutcome.Received(emptyList()))
    }

    @Test
    fun `messages without a chat or with a non numeric chat id keep their text`() {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"result":[{"update_id":1,"message":{"text":"hi"}},""" +
                    """{"update_id":2,"message":{"chat":{"id":"abc"}}},{"update_id":3,"message":{"chat":{"id":5},"text":"/status"}}]}""",
            ),
        )

        assertThat(client.getUpdates(offset = 0L, timeoutSeconds = 1)).isEqualTo(
            UpdatesOutcome.Received(
                listOf(
                    TelegramUpdate(1L, null, "hi"),
                    TelegramUpdate(2L, null, null),
                    TelegramUpdate(3L, 5L, "/status"),
                ),
            ),
        )
    }

    @Test
    fun `a poll without ok or with a non list result fails`() {
        server.enqueue(MockResponse().setBody("""{"result":[]}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"update_id":1}}"""))

        assertThat(client.getUpdates(offset = 0L, timeoutSeconds = 1)).isEqualTo(UpdatesOutcome.Failed)
        assertThat(client.getUpdates(offset = 0L, timeoutSeconds = 1)).isEqualTo(UpdatesOutcome.Failed)
    }
}
