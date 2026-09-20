package com.qkt.dsl.parse

/**
 * Every keyword and operator-word spelling (uppercase) mapped to its [TokenKind]: every token
 * kind except the literal, identifier, EOF and symbolic-operator kinds, spelled as its name.
 */
internal val KEYWORDS: Map<String, TokenKind> =
    TokenKind
        .values()
        .filter {
            it.name !in
                setOf(
                    "NUMBER",
                    "STRING",
                    "IDENT",
                    "EOF",
                    "DURATION",
                    "PLUS",
                    "MINUS",
                    "STAR",
                    "SLASH",
                    "PERCENT",
                    "EQ",
                    "EQEQ",
                    "NEQ",
                    "GT",
                    "LT",
                    "GE",
                    "LE",
                    "AT_SIGN",
                    "COLON",
                    "DOLLAR",
                    "LBRACE",
                    "RBRACE",
                    "LBRACKET",
                    "RBRACKET",
                    "LPAREN",
                    "RPAREN",
                    "COMMA",
                    "DOT",
                    "SEMICOLON",
                )
        }.associateBy { it.name }
