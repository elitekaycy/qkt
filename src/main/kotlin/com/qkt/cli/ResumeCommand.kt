package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `qkt resume [name]` — re-enable new-order submission for one strategy, or all if no name, clearing the stops failed
 * rolls left on continuous streams where that is safe. A stop that stands fails the command, with the reason: the
 * strategy's orders on that stream are still refused. A portfolio name clears the book's halt and the halts it put
 * on its children; a child (`book/alias` or `book:alias`) whose book is halted is refused with the book's reason.
 */
class ResumeCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.positional(0)
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.resume(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else if (e.code == 409) {
                    System.err.println("qkt: error: ${errorText(e.body)}")
                } else {
                    System.err.println("qkt: error: resume failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        val summaries = streamStops(body)
        val standing = summaries.filter { !it.cleared }
        if (args.flag("json")) {
            println(body)
        } else {
            bookHalts(body).forEach { (book, why) -> println("[INFO] cleared the halt on portfolio $book: $why") }
            summaries.filter { it.cleared }.forEach { println("[INFO] ${it.summary}") }
            if (standing.isEmpty()) {
                println("[INFO] resumed ${name ?: "all strategies"}")
            } else {
                standing.forEach { System.err.println("qkt: error: ${it.summary}") }
            }
        }
        return if (standing.isEmpty()) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }

    private class StopSummary(
        val cleared: Boolean,
        val summary: String,
    )

    private fun errorText(body: String): String =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("error")
                .jsonPrimitive.content
        }.getOrNull()
            ?: body

    private fun bookHalts(body: String): Map<String, String> =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["bookHalts"]
                ?.jsonObject
                ?.mapValues { it.value.jsonPrimitive.content }
        }.getOrNull().orEmpty()

    private fun streamStops(body: String): List<StopSummary> =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["streamStops"]
                ?.jsonArray
                ?.map { it.jsonObject }
                ?.map {
                    StopSummary(
                        it.getValue("cleared").jsonPrimitive.boolean,
                        it.getValue("summary").jsonPrimitive.content,
                    )
                }
        }.getOrNull().orEmpty()
}
