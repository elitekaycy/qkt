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
            if (info.resolved.isNotEmpty()) {
                append("<h3>Effective configuration</h3>")
                append("<table><thead><tr><th>Key</th><th>Value</th><th>From</th></tr></thead><tbody>")
                for ((key, entry) in info.resolved.toSortedMap()) {
                    append("<tr><td>${htmlEscape(key)}</td><td>${htmlEscape(entry.value)}</td>")
                    append("<td>${htmlEscape(entry.from)}</td></tr>")
                }
                append("</tbody></table>")
            }
            append(fence("bash", "Command", "repro-cmd", ReproduceHighlighting.highlightShell(info.commandLine)))
            append(
                fence(
                    "qkt",
                    "Strategy (${htmlEscape(info.strategyFile)})",
                    "repro-strategy",
                    ReproduceHighlighting.highlightQkt(info.strategySource),
                ),
            )
            if (info.configSource != null) {
                append(
                    fence(
                        "yaml",
                        "Config (${htmlEscape(info.configFile ?: "qkt.config.yaml")})",
                        "repro-config",
                        ReproduceHighlighting.highlightYaml(info.configSource),
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
