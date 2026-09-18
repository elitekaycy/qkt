package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

abstract class CreateCommandFixture {
    protected fun invoke(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = runMain(argv as Array<String>)
            Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    protected companion object {
        internal val MT5_EXPECTED_FILES =
            listOf(
                ".env.example",
                ".gitignore",
                "README.md",
                "CONFIG.md",
                "Makefile",
                "docker-compose.yml",
                "qkt.config.yaml",
                "scripts/approve-promotions.sh",
                "scripts/verify-live.sh",
                "examples/strategies/README.md",
                "examples/strategies/ema_cross.qkt",
                "examples/strategies/full_strategy.qkt",
                "strategies/.gitkeep",
                "strategies/README.md",
            )
        internal val MINIMAL_EXPECTED_FILES =
            listOf(
                ".env.example",
                ".gitignore",
                "README.md",
                "CONFIG.md",
                "Makefile",
                "docker-compose.yml",
                "qkt.config.yaml",
                "strategies/README.md",
                "strategies/ema_cross.qkt",
            )
        internal val BYBIT_EXPECTED_FILES = MINIMAL_EXPECTED_FILES
    }
}
