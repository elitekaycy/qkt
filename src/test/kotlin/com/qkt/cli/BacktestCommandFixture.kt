package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

abstract class BacktestCommandFixture {
    protected fun runBacktest(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = BacktestCommand(Args(argv as Array<String>)).run()
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    protected fun writeStrategy(
        dir: Path,
        condition: String,
    ): Path {
        val strategy = dir.resolve("strategy.qkt")
        Files.writeString(
            strategy,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN $condition
                THEN LOG "gate"
            """.trimIndent(),
        )
        return strategy
    }
}
