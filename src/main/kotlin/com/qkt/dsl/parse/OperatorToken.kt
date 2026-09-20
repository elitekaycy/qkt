package com.qkt.dsl.parse

/**
 * The operator or punctuation token that starts with [c] (followed by [next]), as its kind and
 * lexeme, or null when [c] starts no operator. Two-character operators are tried before their
 * one-character prefixes (`==` before `=`, `->` before `-`).
 */
internal fun operatorToken(
    c: Char,
    next: Char,
): Pair<TokenKind, String>? =
    when {
        c == '=' && next == '=' -> TokenKind.EQEQ to "=="
        c == '!' && next == '=' -> TokenKind.NEQ to "!="
        c == '<' && next == '>' -> TokenKind.NEQ to "<>"
        c == '>' && next == '=' -> TokenKind.GE to ">="
        c == '<' && next == '=' -> TokenKind.LE to "<="
        c == '=' -> TokenKind.EQ to "="
        c == '>' -> TokenKind.GT to ">"
        c == '<' -> TokenKind.LT to "<"
        c == '+' -> TokenKind.PLUS to "+"
        c == '-' && next == '>' -> TokenKind.ARROW to "->"
        c == '-' -> TokenKind.MINUS to "-"
        c == '*' -> TokenKind.STAR to "*"
        c == '/' -> TokenKind.SLASH to "/"
        c == '%' -> TokenKind.PERCENT to "%"
        c == '@' -> TokenKind.AT_SIGN to "@"
        c == ':' -> TokenKind.COLON to ":"
        c == '$' -> TokenKind.DOLLAR to "$"
        c == '{' -> TokenKind.LBRACE to "{"
        c == '}' -> TokenKind.RBRACE to "}"
        c == '[' -> TokenKind.LBRACKET to "["
        c == ']' -> TokenKind.RBRACKET to "]"
        c == '(' -> TokenKind.LPAREN to "("
        c == ')' -> TokenKind.RPAREN to ")"
        c == ',' -> TokenKind.COMMA to ","
        c == '.' -> TokenKind.DOT to "."
        c == ';' -> TokenKind.SEMICOLON to ";"
        else -> null
    }
