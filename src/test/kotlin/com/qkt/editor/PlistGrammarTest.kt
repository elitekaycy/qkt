package com.qkt.editor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [PlistGrammar] re-encodes every JSON value kind as its property-list element. */
class PlistGrammarTest {
    @Test
    fun `booleans, numbers, nulls and escaped strings map to their plist elements`() {
        val plist = PlistGrammar.render("""{"a": true, "b": false, "n": 3, "z": null, "s": "<x&y>", "l": [1]}""")

        assertThat(plist)
            .contains("<key>a</key>\n  <true/>")
            .contains("<key>b</key>\n  <false/>")
            .contains("<key>n</key>\n  <integer>3</integer>")
            .contains("<key>z</key>\n  <string></string>")
            .contains("<string>&lt;x&amp;y&gt;</string>")
            .contains("<array>\n    <integer>1</integer>\n  </array>")
    }
}
