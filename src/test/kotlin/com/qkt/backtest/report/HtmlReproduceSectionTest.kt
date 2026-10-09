package com.qkt.backtest.report

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlReproduceSectionTest : HtmlHumanValuesFixture() {
    @Test
    fun `reproduce section carries the full command and sources with working copy buttons`() {
        val html =
            render(
                result().copy(
                    reproduction =
                        ReproductionInfo(
                            commandLine = "qkt backtest s.qkt --from 2024-09-30 --to 2024-10-01",
                            strategyFile = "s.qkt",
                            strategySource = "STRATEGY s VERSION 1\nWHEN a < b & c\nTHEN BUY x",
                            configFile = "qkt.config.yaml",
                            configSource = "starting_balance: 10000",
                            qktVersion = "0.55.1",
                            gitSha = "abc123",
                        ),
                ),
            )
        assertThat(html).contains("Reproduce this run")
        assertThat(html).contains("qkt backtest s.qkt")
        assertThat(html).contains("--from")
        assertThat(html).contains("tok-flag")
        assertThat(html).contains("tok-kw\">STRATEGY</span>")
        assertThat(html).contains("b &amp; c")
        assertThat(html).contains("tok-key\">starting_balance</span>")
        assertThat(html).contains("tok-num")
        assertThat(html).contains("starting_balance")
        assertThat(html).contains("10000")
        val buttons = "onclick=\"qktCopy\\(".toRegex().findAll(html).count()
        assertThat(buttons).isEqualTo(3)
        // What the copy button grabs (innerText ~ tags stripped) is the exact source.
        val pre = "<pre id=\"repro-cmd\">(.*?)</pre>".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        assertThat(pre.replace(Regex("<[^>]*>"), ""))
            .isEqualTo("qkt backtest s.qkt --from 2024-09-30 --to 2024-10-01")
        assertThat(html).contains("navigator.clipboard")
        assertThat(html).contains("execCommand")
        assertThat(html).doesNotContain("<script src=")
        assertThat(html).contains("codeblock")
        assertThat(html).contains("codelang")
    }

    @Test
    fun `no reproduce section without reproduction info`() {
        assertThat(render(result())).doesNotContain("Reproduce this run")
    }
}
