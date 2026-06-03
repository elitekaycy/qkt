package com.qkt.cli.daemon

import com.qkt.notify.ChannelConfig
import com.qkt.notify.TelegramClient
import com.qkt.notify.UpdatesOutcome
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * Reads operator commands from a Telegram chat and replies in the same chat. Long-polls
 * getUpdates on a daemon thread; only messages from [chatId] are acted on (others advance the
 * poll offset and are ignored). Network/parse failures back off and retry — the loop never
 * crashes the daemon.
 */
class TelegramCommandChannel(
    private val client: TelegramClient,
    private val chatId: String,
    private val dispatcher: CommandDispatcher,
) : CommandChannel {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start() {
        if (running) return
        running = true
        thread =
            Thread({ runLoop() }, "telegram-commands").apply {
                isDaemon = true
                start()
            }
    }

    private fun runLoop() {
        var offset = 0L
        while (running) {
            when (val outcome = client.getUpdates(offset, POLL_TIMEOUT_SECONDS)) {
                is UpdatesOutcome.Received -> {
                    for (update in outcome.updates) {
                        offset = update.updateId + 1
                        val text = update.text ?: continue
                        if (update.chatId?.toString() != chatId) continue
                        val reply = dispatcher.dispatch(CommandParser.parse(text))
                        client.send(reply.text)
                    }
                }
                UpdatesOutcome.Failed -> backoff()
            }
        }
    }

    private fun backoff() {
        try {
            Thread.sleep(BACKOFF_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        thread?.join(CLOSE_JOIN_MS)
    }

    companion object {
        private const val TELEGRAM_API = "https://api.telegram.org"
        private const val POLL_TIMEOUT_SECONDS = 25
        private const val BACKOFF_MS = 5_000L
        private const val CLOSE_JOIN_MS = 2_000L
        private val log = LoggerFactory.getLogger(TelegramCommandChannel::class.java)

        /**
         * Builds a command channel from a channel config, or null (with a warning) when the
         * Telegram credentials are missing. The poll client's read timeout exceeds the long-poll
         * timeout so getUpdates returns normally.
         */
        fun from(
            config: ChannelConfig,
            control: DaemonControl,
        ): TelegramCommandChannel? {
            val botToken = config.settings["bot_token"].orEmpty()
            val chatId = config.settings["chat_id"].orEmpty()
            if (botToken.isEmpty() || chatId.isEmpty()) {
                log.warn("[notify] telegram commands enabled but bot_token/chat_id missing")
                return null
            }
            val http =
                OkHttpClient
                    .Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout((POLL_TIMEOUT_SECONDS + 5).toLong(), TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .build()
            val client = TelegramClient(TELEGRAM_API, botToken, chatId, http)
            return TelegramCommandChannel(client, chatId, CommandDispatcher(control))
        }
    }
}
