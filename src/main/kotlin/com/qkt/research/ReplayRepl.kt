package com.qkt.research

import java.io.BufferedReader

/**
 * The read-eval-print loop: prints a prompt, reads a line, dispatches it to the
 * [ReplaySession], renders the result, and repeats until `quit` or end-of-input.
 * Streams are injected so the loop is testable without real stdin/stdout.
 */
class ReplayRepl(
    private val session: ReplaySession,
) {
    /** Run until the user quits or [input] is exhausted. Writes prompts + rendered output to [out]. */
    fun run(
        input: BufferedReader,
        out: Appendable,
    ) {
        while (true) {
            out.append("> ")
            val line = input.readLine() ?: break
            val cmd = ReplayCommand.parse(line)
            val result = session.dispatch(cmd)
            out.append(TapeRenderer.render(result)).append("\n")
            if (result.quit) break
        }
    }
}
