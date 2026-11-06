package com.qkt.lsp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Hover on an undeclared word shows nothing, and text with no section header above it has no snippet scope. */
class HoverAndScopeFallbackTest {
    @Test
    fun `hovering a word that is no indicator, function, keyword or alias shows nothing`() {
        assertThat(HoverProvider.hover("LET zzqq = 1", 0, 5, null)).isNull()
    }

    @Test
    fun `a line under no recognised section header has no snippet scope`() {
        assertThat(CompletionProvider.scopeAt("# notes\nfoo = 1\n\n", 2)).isNull()
    }
}
