package com.qkt.backtest.report

/**
 * The "Reproduce this run" section of the HTML report: the exact command, strategy and config
 * that produced the result, rendered as markdown-style fenced code blocks with syntax
 * highlighting and a copy button each. Highlighting is done server-side (no JS highlighting
 * libraries), so the file renders fully offline; the only script is the tiny copy helper.
 */
internal object HtmlReproduceSection {
    fun render(info: ReproductionInfo): String =
        buildString {
            append("<p>Everything below re-runs this exact backtest (qkt ${htmlEscape(info.qktVersion)}, ")
            append("git ${htmlEscape(info.gitSha)}). Send this file and the recipient needs nothing else.</p>")
            append(fence("bash", "Command", "repro-cmd", highlightShell(info.commandLine)))
            append(
                fence(
                    "qkt",
                    "Strategy (${htmlEscape(info.strategyFile)})",
                    "repro-strategy",
                    highlightQkt(info.strategySource),
                ),
            )
            if (info.configSource != null) {
                append(
                    fence(
                        "yaml",
                        "Config (${htmlEscape(info.configFile ?: "qkt.config.yaml")})",
                        "repro-config",
                        highlightYaml(info.configSource),
                    ),
                )
            }
            append(copyScript())
        }

    private fun fence(
        language: String,
        title: String,
        id: String,
        bodyHtml: String,
    ): String =
        "<div class=\"codeblock\">" +
            "<div class=\"codehead\"><span>$title</span>" +
            "<span><span class=\"codelang\">$language</span> " +
            "<button type=\"button\" onclick=\"qktCopy('$id',this)\">Copy</button></span></div>" +
            "<pre id=\"$id\">$bodyHtml</pre></div>"

    // -- highlighting: regex over raw text, emitting escaped spans (never nested) --

    private val stringRe = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
    private val numberRe = Regex("\\b\\d[\\d_]*(?:\\.\\d+)?\\b")
    private val shellFlagRe = Regex("(^|\\s)(--[\\w][\\w-]*)")
    private val yamlKeyRe = Regex("(?m)^(\\s*[\\w][\\w.\\-]*)(:)")
    private val yamlCommentRe = Regex("#.*$")
    private val qktKeywords =
        setOf(
            "STRATEGY", "VERSION", "SYMBOLS", "RULES", "WHEN", "THEN", "BUY", "SELL", "CLOSE",
            "SIZING", "EVERY", "AND", "OR", "NOT", "CROSSES", "ABOVE", "BELOW", "LOG",
            "BRACKET", "STOP", "LOSS", "TAKE", "PROFIT", "DEFAULTS", "RISK", "OF",
        )

    private fun spans(
        text: String,
        pattern: Regex,
        cls: (MatchResult) -> String?,
    ): String {
        val sb = StringBuilder()
        var at = 0
        for (m in pattern.findAll(text)) {
            val c = cls(m) ?: continue
            sb.append(htmlEscape(text.substring(at, m.range.first)))
            sb.append("<span class=\"$c\">${htmlEscape(m.value)}</span>")
            at = m.range.last + 1
        }
        sb.append(htmlEscape(text.substring(at)))
        return sb.toString()
    }

    private fun highlightShell(cmd: String): String {
        // Flags first, then strings and numbers inside the gaps (kept simple: one pass for
        // flags, strings/numbers stay plain in shell — the eye needs the flags).
        return spans(cmd, shellFlagRe) { m ->
            if (m.groups[2] != null) "__FLAG__" else null
        }.replace("__FLAG__", "tok-flag")
            .let { flagged ->
                // Re-wrap: split out the flagged spans is overkill; instead highlight strings
                // on the raw command and merge — simpler: strings get spans via a second pass
                // that skips existing tags.
                highlightOutsideTags(flagged, stringRe, "tok-str")
            }
    }

    private fun highlightYaml(src: String): String =
        src.lines().joinToString("\n") { line ->
            val commentAt = line.indexOf('#')
            val code = if (commentAt >= 0) line.substring(0, commentAt) else line
            val comment = if (commentAt >= 0) line.substring(commentAt) else null
            val keyed =
                yamlKeyRe.replace(code) { m ->
                    "<span class=\"tok-key\">${htmlEscape(m.groups[1]!!.value)}</span>${m.groups[2]!!.value}"
                }
            val valued = highlightOutsideTags(keyed, stringRe, "tok-str")
            val numbered = highlightOutsideTags(valued, numberRe, "tok-num")
            if (comment != null) numbered + "<span class=\"tok-com\">${htmlEscape(comment)}</span>" else numbered
        }

    private fun highlightQkt(src: String): String =
        src.lines().joinToString("\n") { line ->
            var html = htmlEscape(line)
            for (kw in qktKeywords) {
                html = html.replace(Regex("\\b$kw\\b"), "<span class=\"tok-kw\">$kw</span>")
            }
            html = highlightOutsideTags(html, stringReOnEscaped(), "tok-str")
            highlightOutsideTags(html, numberRe, "tok-num")
        }

    /** String regex applied to already-escaped HTML, where quotes are `&quot;`. */
    private fun stringReOnEscaped(): Regex = Regex("&quot;(?:[^&]|&(?!quot;))*?&quot;")

    /**
     * Apply [pattern] to [html], skipping text already inside `<span>` elements (matched text is
     * raw-escaped already for yaml keys/keywords; for plain-text passes the value is escaped at
     * emission by [spans]).
     */
    private fun highlightOutsideTags(
        html: String,
        pattern: Regex,
        cls: String,
    ): String {
        val tagRe = Regex("<span class=\"[^\"]*\">.*?</span>")
        val sb = StringBuilder()
        var at = 0
        val tags = tagRe.findAll(html).toList()
        fun paint(
            from: Int,
            to: Int,
        ) {
            val seg = html.substring(from, to)
            // Segment may still contain raw text (shell path) or escaped text (yaml/qkt path);
            // spans() escapes, so only use it on raw segments: detect by absence of "&" or "<".
            if ('<' !in seg && '&' !in seg) {
                sb.append(spans(seg, pattern) { cls })
            } else {
                var sAt = 0
                for (m in pattern.findAll(seg)) {
                    sb.append(seg.substring(sAt, m.range.first))
                    sb.append("<span class=\"$cls\">${m.value}</span>")
                    sAt = m.range.last + 1
                }
                sb.append(seg.substring(sAt))
            }
        }
        for (t in tags) {
            paint(at, t.range.first)
            sb.append(t.value)
            at = t.range.last + 1
        }
        paint(at, html.length)
        return sb.toString()
    }

    private fun copyScript(): String =
        """
        <script>
        function qktCopy(id, btn) {
          var text = document.getElementById(id).innerText;
          function done() { var old = btn.innerText; btn.innerText = "Copied!"; setTimeout(function() { btn.innerText = old; }, 1500); }
          if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, function() { qktCopyFallback(text, done); });
          } else {
            qktCopyFallback(text, done);
          }
        }
        function qktCopyFallback(text, done) {
          var ta = document.createElement("textarea");
          ta.value = text;
          document.body.appendChild(ta);
          ta.select();
          try { document.execCommand("copy"); done(); } catch (e) {}
          document.body.removeChild(ta);
        }
        </script>
        """.trimIndent()
}
