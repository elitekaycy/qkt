package com.qkt.backtest.report

/**
 * The "Reproduce this run" section of the HTML report: the exact command, strategy and config
 * that produced the result, each with a copy button. Self-contained like the rest of the report:
 * one inline script, no external assets, clipboard API with a textarea fallback.
 */
internal object HtmlReproduceSection {
    fun render(info: ReproductionInfo): String =
        buildString {
            append("<p>Everything below re-runs this exact backtest (qkt ${htmlEscape(info.qktVersion)}, ")
            append("git ${htmlEscape(info.gitSha)}). Send this file and the recipient needs nothing else.</p>")
            append(block("Command", "repro-cmd", info.commandLine))
            append(block("Strategy (${htmlEscape(info.strategyFile)})", "repro-strategy", info.strategySource))
            if (info.configSource != null) {
                append(
                    block(
                        "Config (${htmlEscape(info.configFile ?: "qkt.config.yaml")})",
                        "repro-config",
                        info.configSource,
                    ),
                )
            }
            append(copyScript())
        }

    private fun block(
        title: String,
        id: String,
        body: String,
    ): String =
        "<h3>$title</h3>" +
            "<button type=\"button\" onclick=\"qktCopy('$id',this)\">Copy</button>" +
            "<pre id=\"$id\">${htmlEscape(body)}</pre>"

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
