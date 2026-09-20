package com.qkt.parity

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat

/** Unit instrument registry and the bounded waits the portfolio deployer parity tests poll with. */
internal object PortfolioDeployerParityFixtures {
    fun unitRegistry(): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = BigDecimal("1000"),
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    fun awaitJournal(strategyDir: Path): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            val journal =
                if (Files.isDirectory(strategyDir)) {
                    Files.list(strategyDir).use { files -> files.findFirst().orElse(null) }
                } else {
                    null
                }
            if (journal != null) {
                val body = Files.readString(journal)
                if (body.contains("\"kind\":\"risk-rejected\"")) return body
            }
            Thread.sleep(10L)
        }
        error("risk rejection journal was not written for $strategyDir")
    }

    fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertThat(condition()).isTrue()
    }
}
