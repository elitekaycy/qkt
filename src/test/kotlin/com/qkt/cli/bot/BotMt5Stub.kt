package com.qkt.cli.bot

import com.qkt.cli.Args
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** What one `qkt bot` verb returned: its exit code, stdout and stderr. */
internal data class BotRun(
    val code: Int,
    val out: String,
    val err: String,
)

/**
 * An MT5 gateway answering canned bodies by path, for driving `qkt bot` one-shot verbs through their real
 * entry point. `/fetch_data_range` answers one closed bar per request, starting at the next hour boundary
 * after the requested `start`, so a bar read never depends on the test's clock.
 */
internal class BotMt5Stub : AutoCloseable {
    val responses = ConcurrentHashMap<String, Pair<Int, String>>()
    val requests = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().decodeToString()
            requests += "${exchange.requestMethod} $path $body".trim()
            val (status, payload) =
                if (path ==
                    "/fetch_data_range"
                ) {
                    200 to bar(exchange.requestURI.rawQuery)
                } else {
                    responses[path] ?: (404 to "{}")
                }
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    private fun bar(query: String): String {
        val start =
            query.split('&').first { it.startsWith("start=") }.removePrefix("start=").let {
                URLDecoder.decode(it, "UTF-8")
            }
        val at =
            LocalDateTime
                .parse(
                    start,
                ).toInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .plus(1, ChronoUnit.HOURS)
        return """[{"time":"$at","open":2650,"high":2655,"low":2645,"close":2652,"tick_volume":10,"spread":5}]"""
    }

    /** Writes a config with one MT5 broker `exness` on this stub and returns its path. */
    fun config(dir: Path): Path {
        val file = dir.resolve("qkt.config.yaml")
        Files.writeString(
            file,
            """
            |brokers:
            |  exness:
            |    type: mt5
            |    gateway_url: $url
            |    symbol_suffix: m
            |    magic: 777001
            """.trimMargin(),
        )
        return file
    }

    /** Runs `qkt bot <argv>` against this stub, with state under [dir]. */
    fun bot(
        dir: Path,
        vararg argv: String,
    ): BotRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val prevOut = System.out
        val prevErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        val code =
            try {
                BotCommand(
                    Args(arrayOf("bot", *argv, "--config", config(dir).toString(), "--state-dir", dir.toString())),
                ).run()
            } finally {
                System.setOut(prevOut)
                System.setErr(prevErr)
            }
        return BotRun(code, out.toString(), err.toString())
    }

    override fun close() = server.stop(0)

    companion object {
        const val ACCOUNT =
            """{"login":42,"server":"Exness-Demo","balance":9800,"equity":10000,"currency":"USD","leverage":500,""" +
                """"margin_mode":2,"margin":120.5,"margin_free":9879.5,"margin_level":8298.7,"profit":200}"""

        fun position(
            ticket: Long,
            comment: String = "",
            sl: String = "0",
            tp: String = "0",
        ) = """{"ticket":$ticket,"symbol":"XAUUSDm","type":0,"volume":0.5,"price_open":2650,"sl":$sl,"tp":$tp,""" +
            """"profit":12.5,"magic":777001,"comment":"$comment","swap":-1.2,"price_current":2675,"time_msc":0}"""

        fun order(
            ticket: Long,
            comment: String = "",
        ) = """{"ticket":$ticket,"symbol":"XAUUSDm","type_str":"BUY_LIMIT","volume_current":0.2,"price_open":2600,""" +
            """"sl":2590,"tp":2700,"magic":777001,"comment":"$comment","time_expiration":0}"""

        const val DONE = """{"result":{"retcode":10009,"order":0,"deal":77,"price":2651,"comment":"done"}}"""
        const val REFUSED = """{"result":{"retcode":10019,"order":0,"deal":0,"price":0,"comment":"No money"}}"""
    }
}
