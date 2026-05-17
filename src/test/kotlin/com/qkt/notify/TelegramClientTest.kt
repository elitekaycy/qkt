package com.qkt.notify

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelegramClientTest {
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
    fun `send POSTs JSON body with chat_id and text to bot path`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val result = client.send("hello world")
        assertThat(result).isInstanceOf(TelegramClient.Outcome.Ok::class.java)

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/botTOKEN/sendMessage")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"chat_id\":\"CHAT\"")
        assertThat(body).contains("\"text\":\"hello world\"")
    }

    @Test
    fun `429 with Retry-After returns RateLimited carrying the value in seconds`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        val result = client.send("x")
        assertThat(result).isEqualTo(TelegramClient.Outcome.RateLimited(retryAfterMs = 7_000L))
    }

    @Test
    fun `5xx returns TransientError`() {
        server.enqueue(MockResponse().setResponseCode(502))
        val result = client.send("x")
        assertThat(result).isInstanceOf(TelegramClient.Outcome.TransientError::class.java)
    }

    @Test
    fun `401 returns AuthFailed`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(client.send("x")).isEqualTo(TelegramClient.Outcome.AuthFailed)
    }

    @Test
    fun `403 returns AuthFailed`() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(client.send("x")).isEqualTo(TelegramClient.Outcome.AuthFailed)
    }

    @Test
    fun `400 returns BadRequest with body text`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"description":"bad chat id"}"""))
        val result = client.send("x")
        assertThat(result).isInstanceOf(TelegramClient.Outcome.BadRequest::class.java)
        val br = result as TelegramClient.Outcome.BadRequest
        assertThat(br.body).contains("bad chat id")
    }

    @Test
    fun `JSON body escapes embedded quotes and backslashes`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.send("""quote " and \ backslash""")
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"text\":\"quote \\\" and \\\\ backslash\"")
    }
}
