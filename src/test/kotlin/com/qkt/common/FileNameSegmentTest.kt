package com.qkt.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FileNameSegmentTest {
    private val windowsForbidden = Regex("""[<>:"|?*]""")

    @Test
    fun `on windows a venue-qualified symbol and a portfolio child id become legal names`() {
        assertThat(FileNameSegment.encode("CME:ES", windows = true)).isEqualTo("CME%3AES")
        assertThat(FileNameSegment.encode("aggregate_book:b", windows = true)).isEqualTo("aggregate_book%3Ab")
    }

    @Test
    fun `on windows every reserved character round-trips and separators are kept`() {
        val id = """A<B>C:D"E|F?G*H%I/J"""
        val name = FileNameSegment.encode(id, windows = true)

        assertThat(windowsForbidden.containsMatchIn(name)).isFalse
        assertThat(name).contains("/")
        assertThat(FileNameSegment.decode(name, windows = true)).isEqualTo(id)
    }

    @Test
    fun `plain ids keep their name on windows`() {
        assertThat(FileNameSegment.encode("XAUUSDm", windows = true)).isEqualTo("XAUUSDm")
    }

    @Test
    fun `elsewhere names are left exactly as they are`() {
        assertThat(FileNameSegment.encode("CME:ES", windows = false)).isEqualTo("CME:ES")
        assertThat(FileNameSegment.decode("CME%3AES", windows = false)).isEqualTo("CME%3AES")
    }
}
