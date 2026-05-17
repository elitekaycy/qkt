package com.qkt.cli

import com.qkt.notify.NotifyEventKind
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigNotifyTest {
    @Test
    fun `load returns disabled notify when block is absent`(
        @TempDir tmp: Path,
    ) {
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            source: local
            data_root: ./data
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.notify.telegram.enabled).isFalse()
    }

    @Test
    fun `load parses telegram notify block with env expansion`(
        @TempDir tmp: Path,
    ) {
        System.setProperty("TG_TOKEN_TEST", "T")
        System.setProperty("TG_CHAT_TEST", "C")
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            source: local
            data_root: ./data
            notify:
              telegram:
                enabled: true
                bot_token: ${'$'}{TG_TOKEN_TEST}
                chat_id: ${'$'}{TG_CHAT_TEST}
                daily_summary_utc: "01:23"
                events:
                  - order_rejected
                  - halted
            """.trimIndent(),
        )
        val c = Config.load(cfg)
        assertThat(c.notify.telegram.enabled).isTrue()
        assertThat(c.notify.telegram.botToken).isEqualTo("T")
        assertThat(c.notify.telegram.chatId).isEqualTo("C")
        assertThat(c.notify.telegram.dailySummaryUtc).isEqualTo("01:23")
        assertThat(c.notify.telegram.events).containsExactlyInAnyOrder(
            NotifyEventKind.ORDER_REJECTED,
            NotifyEventKind.HALTED,
        )
    }
}
