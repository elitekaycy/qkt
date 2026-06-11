package com.qkt.cli

import com.qkt.observe.insights.InsightsEventFamily
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConfigInsightsTest {
    @AfterEach
    fun cleanup() {
        System.clearProperty("INSIGHTS_ENABLED_TESTVAR")
        System.clearProperty("INSIGHTS_TOKEN_TESTVAR")
    }

    @Test
    fun `load returns disabled insights when block is absent`(
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
        assertThat(Config.load(cfg).insights.enabled).isFalse()
    }

    @Test
    fun `load parses the insights block through env interpolation`(
        @TempDir tmp: Path,
    ) {
        System.setProperty("INSIGHTS_ENABLED_TESTVAR", "true")
        System.setProperty("INSIGHTS_TOKEN_TESTVAR", "tok123")
        val cfg = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            cfg,
            """
            source: local
            data_root: ./data
            insights:
              enabled: ${'$'}{INSIGHTS_ENABLED_TESTVAR:-false}
              url: http://insights:8420/ingest
              instance_id: qkt-prod
              token: ${'$'}{INSIGHTS_TOKEN_TESTVAR:-}
              events: [trade, order, snapshot, log]
            """.trimIndent(),
        )
        val ins = Config.load(cfg).insights
        assertThat(ins.enabled).isTrue()
        assertThat(ins.url).isEqualTo("http://insights:8420/ingest")
        assertThat(ins.instanceId).isEqualTo("qkt-prod")
        assertThat(ins.token).isEqualTo("tok123")
        assertThat(ins.events)
            .containsExactlyInAnyOrder(
                InsightsEventFamily.TRADE,
                InsightsEventFamily.ORDER,
                InsightsEventFamily.SNAPSHOT,
                InsightsEventFamily.LOG,
            )
    }
}
