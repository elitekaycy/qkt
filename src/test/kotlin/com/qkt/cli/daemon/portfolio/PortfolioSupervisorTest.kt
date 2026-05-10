package com.qkt.cli.daemon.portfolio

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioSupervisorTest {
    @Test
    fun `start and stop toggles running`() {
        val ast =
            PortfolioAst(
                name = "p",
                version = 1,
                streams = emptyList(),
                imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
                rules = listOf(AlwaysRun("a")),
            )
        val supervisor =
            PortfolioSupervisor(
                ast = ast,
                children = emptyList(),
                marketSource = null,
            )
        assertThat(supervisor.running).isFalse
        supervisor.start()
        assertThat(supervisor.running).isTrue
        supervisor.stop()
        assertThat(supervisor.running).isFalse
    }
}
