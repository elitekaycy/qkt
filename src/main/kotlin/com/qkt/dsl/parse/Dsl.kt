package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import java.nio.file.Files
import java.nio.file.Path

object Dsl {
    fun parse(source: String): ParseResult<StrategyAst> = Parser(Lexer(source).tokenize()).parseStrategy()

    fun parseFile(path: Path): ParseResult<StrategyAst> = parse(Files.readString(path))
}
