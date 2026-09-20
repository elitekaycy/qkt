package com.qkt.parity

import java.nio.file.Files
import java.nio.file.Path

/** Writes the `.qkt` strategies the generated reentry parity tests replay. */
internal object ReentryStrategyFiles {
    fun writeStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close >= 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close <= 90 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    fun writeTimedReentryStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 90 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 110 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 120 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 80 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    fun writePendingReentryStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close >= 100 AND POSITION.x = 0 AND OPEN_ORDERS.x = 0
              THEN BUY x SIZING 1 ORDER_TYPE = LIMIT AT 95 TIF GTD NOW + 4m

              WHEN x.close >= 110 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    fun writeLossStreakResetStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 90 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 120 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 140 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 150 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 130 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 160 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 170 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }

    fun writeDailyHaltResetStrategy(
        tempDir: Path,
        id: String,
    ): Path {
        val strategyPath = tempDir.resolve("$id.qkt")
        Files.writeString(
            strategyPath,
            """
            STRATEGY $id VERSION 1
            SYMBOLS x = BACKTEST:X EVERY 1m
            RULES
              WHEN x.close = 100 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 90 AND POSITION.x != 0
              THEN CLOSE x

              WHEN x.close = 110 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 120 AND POSITION.x = 0
              THEN BUY x SIZING 1

              WHEN x.close = 140 AND POSITION.x != 0
              THEN CLOSE x
            """.trimIndent(),
        )
        return strategyPath
    }
}
