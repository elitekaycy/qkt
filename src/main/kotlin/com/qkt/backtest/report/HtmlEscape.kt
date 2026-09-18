package com.qkt.backtest.report

/** Escapes the HTML metacharacters `&`, `<`, `>` and `"` in [s] for use in element text or attributes. */
internal fun htmlEscape(s: String): String =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
