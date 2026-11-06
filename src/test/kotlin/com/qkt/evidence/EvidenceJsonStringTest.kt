package com.qkt.evidence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EvidenceJsonStringTest {
    @Test
    fun `json strings escape quotes backslashes and every control character`() {
        assertThat(EvidenceJson.jsonString("q\"b\\n\nr\rt\tu\u001f!"))
            .isEqualTo("\"q\\\"b\\\\n\\nr\\rt\\tu\\u001f!\"")
    }
}
