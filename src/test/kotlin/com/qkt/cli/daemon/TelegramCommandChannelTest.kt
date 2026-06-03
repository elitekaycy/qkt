package com.qkt.cli.daemon

import com.qkt.notify.ChannelConfig
import com.qkt.notify.TelegramClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelegramCommandChannelTest {
    private lateinit var server: MockWebServer
    private var channel: TelegramCommandChannel? = null

    private val getUpdatesCalls = AtomicInteger(0)
    private val sendBody = AtomicReference<String?>(null)
    private val sendLatch = CountDownLatch(1)
    private val secondPollLatch = CountDownLatch(1)
    private val getUpdatesOffsets = mutableListOf<String?>()

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun teardown() {
        channel?.close()
        server.shutdown()
    }

    private fun start(firstUpdates: String) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.contains("getUpdates") -> {
                            synchronized(getUpdatesOffsets) {
                                getUpdatesOffsets += offsetOf(path)
                            }
                            val callIndex = getUpdatesCalls.getAndIncrement()
                            if (callIndex >= 1) secondPollLatch.countDown()
                            if (callIndex == 0) {
                                MockResponse().setResponseCode(200).setBody(firstUpdates)
                            } else {
                                Thread.sleep(50)
                                MockResponse().setResponseCode(200).setBody("""{"ok":true,"result":[]}""")
                            }
                        }
                        path.contains("sendMessage") -> {
                            sendBody.set(request.body.readUtf8())
                            sendLatch.countDown()
                            MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val http =
            OkHttpClient
                .Builder()
                .connectTimeout(2, SECONDS)
                .readTimeout(2, SECONDS)
                .writeTimeout(2, SECONDS)
                .build()
        val client = TelegramClient(server.url("/").toString().trimEnd('/'), "TOK", "999", http)
        channel = TelegramCommandChannel(client, "999", CommandDispatcher(EmptyControl)).also { it.start() }
    }

    @Test
    fun `command from the configured chat triggers a reply`() {
        start(
            """{"ok":true,"result":[{"update_id":5,"message":{"message_id":1,""" +
                """"chat":{"id":999,"type":"private"},"text":"/status"}}]}""",
        )

        assertThat(sendLatch.await(3, SECONDS)).isTrue()
        assertThat(sendBody.get()).contains("no strategies deployed")
    }

    @Test
    fun `message from a foreign chat is ignored`() {
        start(
            """{"ok":true,"result":[{"update_id":7,"message":{"message_id":2,""" +
                """"chat":{"id":888,"type":"private"},"text":"/halt"}}]}""",
        )

        assertThat(secondPollLatch.await(3, SECONDS)).isTrue()
        assertThat(sendBody.get()).isNull()
        val offsets = synchronized(getUpdatesOffsets) { getUpdatesOffsets.toList() }
        assertThat(offsets).contains("8")
    }

    @Test
    fun `from builds a channel when credentials are present`() {
        val built =
            TelegramCommandChannel.from(
                ChannelConfig(
                    type = "telegram",
                    enabled = true,
                    commands = true,
                    settings = mapOf("bot_token" to "T", "chat_id" to "C"),
                ),
                EmptyControl,
            )
        assertThat(built).isNotNull()
    }

    @Test
    fun `from returns null when bot_token is missing`() {
        val built =
            TelegramCommandChannel.from(
                ChannelConfig(
                    type = "telegram",
                    enabled = true,
                    commands = true,
                    settings = mapOf("chat_id" to "C"),
                ),
                EmptyControl,
            )
        assertThat(built).isNull()
    }

    @Test
    fun `from returns null when chat_id is missing`() {
        val built =
            TelegramCommandChannel.from(
                ChannelConfig(
                    type = "telegram",
                    enabled = true,
                    commands = true,
                    settings = mapOf("bot_token" to "T"),
                ),
                EmptyControl,
            )
        assertThat(built).isNull()
    }

    private fun offsetOf(path: String): String? = Regex("offset=([^&]+)").find(path)?.groupValues?.get(1)

    private object EmptyControl : DaemonControl {
        override fun halt(target: Target): ControlResult = ControlResult(affected = emptyList())

        override fun resume(target: Target): ControlResult = ControlResult(affected = emptyList())

        override fun status(): StatusReport = StatusReport(emptyList())
    }
}
