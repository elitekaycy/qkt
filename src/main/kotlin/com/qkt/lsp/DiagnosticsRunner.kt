package com.qkt.lsp

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseError
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Runs qkt's real parser over a document and turns what it reports into LSP diagnostics,
 * so the squiggles an editor shows match exactly what `qkt parse` prints on the command line.
 */
object DiagnosticsRunner {
    /**
     * Outcome of analyzing one document: the parsed AST (null when parsing failed) and the
     * diagnostics to publish to the editor.
     */
    data class Analysis(
        val parsed: ParsedFile?,
        val diagnostics: List<Diagnostic>,
    )

    /** Pulls the 1-based line and column out of a lexer error message, e.g. "... at line 8 col 3". */
    private val LEX_POSITION = Regex("""line (\d+) col (\d+)""")

    fun analyze(text: String): Analysis {
        val widths = tokenWidths(text)
        return try {
            when (val result = Dsl.parseAny(text)) {
                is ParseResult.Success -> Analysis(result.value, emptyList())
                is ParseResult.Failure -> Analysis(null, result.errors.map { it.toDiagnostic(widths) })
            }
        } catch (t: Throwable) {
            // The lexer throws on malformed input (unterminated string, stray character) rather
            // than returning a Failure; recover the position from its message.
            Analysis(null, listOf(lexerDiagnostic(t)))
        }
    }

    /**
     * Maps each token's 1-based (line, col) start to its width in UTF-16 code units, so a point
     * error can be widened to underline the whole offending token. Re-lexing is safe here: the
     * parser only returns a Failure when lexing already succeeded.
     */
    private fun tokenWidths(text: String): Map<Pair<Int, Int>, Int> =
        try {
            Lexer(text).tokenize().associate { (it.line to it.col) to it.lexeme.length }
        } catch (t: Throwable) {
            emptyMap()
        }

    private fun ParseError.toDiagnostic(widths: Map<Pair<Int, Int>, Int>): Diagnostic =
        diagnostic(line, col, widths[line to col] ?: 1, message)

    private fun lexerDiagnostic(t: Throwable): Diagnostic {
        val match = LEX_POSITION.find(t.message ?: "")
        val line = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val col = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
        return diagnostic(line, col, 1, t.message ?: "lex error")
    }

    private fun diagnostic(
        line: Int,
        col: Int,
        width: Int,
        message: String,
    ): Diagnostic {
        val start = Position(line - 1, col - 1)
        val end = Position(line - 1, col - 1 + width)
        return Diagnostic(Range(start, end), message).apply {
            severity = DiagnosticSeverity.Error
            source = "qkt"
        }
    }
}
