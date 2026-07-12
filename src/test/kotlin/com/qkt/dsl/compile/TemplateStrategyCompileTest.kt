package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class TemplateStrategyCompileTest {
    @Test
    fun `shipped live templates compile through the daemon compiler path`() {
        val templates =
            listOf(
                "src/main/resources/templates/mt5/examples/strategies/ema_cross.qkt",
                "src/main/resources/templates/bybit/strategies/ema_cross.qkt",
            )

        for (template in templates) {
            assertThatCode {
                when (val parsed = Dsl.parseFile(Path.of(template))) {
                    is ParseResult.Success -> AstCompiler().compile(parsed.value)
                    is ParseResult.Failure ->
                        error(parsed.errors.joinToString("; ") { "${it.line}:${it.col} ${it.message}" })
                }
            }.describedAs(template).doesNotThrowAnyException()
        }
    }
}
