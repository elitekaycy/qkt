"""Pygments lexer for the qkt strategy DSL (``.qkt`` files).

Highlights ``STRATEGY``/``PORTFOLIO`` blocks, action keywords, indicator
function calls, broker-prefixed symbols (``EXNESS:EURUSD``), stream-field
access (``btc.close``), literals, and bracket/sizing modifiers.
"""

from pygments.lexer import RegexLexer, bygroups, include, words
from pygments.token import (
    Comment,
    Keyword,
    Name,
    Number,
    Operator,
    Punctuation,
    String,
    Text,
)

__all__ = ["QktLexer"]


class QktLexer(RegexLexer):
    name = "qkt"
    aliases = ["qkt"]
    filenames = ["*.qkt"]

    BLOCK_KEYWORDS = (
        "STRATEGY",
        "PORTFOLIO",
        "VERSION",
        "SYMBOLS",
        "STATE",
        "RULES",
        "DEFAULTS",
        "GUARDS",
        "CHILDREN",
        "LET",
        "AS",
    )

    CONTROL_KEYWORDS = (
        "WHEN",
        "THEN",
        "AND",
        "OR",
        "NOT",
        "IF",
        "ELSE",
        "FOR",
        "EACH",
        "IN",
    )

    ACTION_KEYWORDS = (
        "BUY",
        "SELL",
        "CANCEL",
        "CANCEL_ALL",
        "LOG",
        "CLOSE",
        "FLATTEN",
    )

    MODIFIER_KEYWORDS = (
        "BRACKET",
        "STACK",
        "STOP_LOSS",
        "TAKE_PROFIT",
        "STOP",
        "LIMIT",
        "MARKET",
        "TRAILING",
        "STOP_LIMIT",
        "SIZING",
        "RISK",
        "PCT",
        "PERCENT",
        "QTY",
        "BY",
        "AT",
        "WITHIN",
        "SPACING",
        "EVERY",
        "WEIGHT",
        "TIF",
        "GTC",
        "IOC",
        "FOK",
        "DAY",
        "EXPIRES",
        "WARMUP",
        "BARS",
        "TICKS",
        "DURATION",
    )

    OPERATOR_KEYWORDS = (
        "CROSSES",
        "ABOVE",
        "BELOW",
        "BETWEEN",
        "IS",
    )

    LITERAL_KEYWORDS = (
        "TRUE",
        "FALSE",
        "NULL",
        "INFO",
        "WARN",
        "ERROR",
        "DEBUG",
    )

    # Built-in indicators / functions the parser knows
    INDICATOR_FUNCS = (
        "ema",
        "sma",
        "wma",
        "rsi",
        "atr",
        "vwap",
        "macd",
        "bollinger",
        "stoch",
        "obv",
        "adx",
        "cci",
        "mfi",
        "williams",
        "donchian",
        "keltner",
        "highest",
        "lowest",
        "crosses_above",
        "crosses_below",
        "abs",
        "max",
        "min",
        "sqrt",
        "log",
        "exp",
        "floor",
        "ceil",
        "round",
        "avg",
        "sum",
        "count",
    )

    tokens = {
        "root": [
            include("whitespace"),
            include("comments"),

            # block-level keywords
            (words(BLOCK_KEYWORDS, prefix=r"\b", suffix=r"\b"),
             Keyword.Namespace),
            # control flow
            (words(CONTROL_KEYWORDS, prefix=r"\b", suffix=r"\b"),
             Keyword),
            # actions (verbs that emit signals)
            (words(ACTION_KEYWORDS, prefix=r"\b", suffix=r"\b"),
             Keyword.Reserved),
            # modifiers (BRACKET, STACK, SIZING, ...)
            (words(MODIFIER_KEYWORDS, prefix=r"\b", suffix=r"\b"),
             Keyword.Pseudo),
            # comparison operators expressed as words
            (words(OPERATOR_KEYWORDS, prefix=r"\b", suffix=r"\b"),
             Operator.Word),
            # literal constants
            (words(LITERAL_KEYWORDS, prefix=r"\b", suffix=r"\b"),
             Keyword.Constant),

            # broker:symbol prefixed instrument reference
            # e.g. `EXNESS:EURUSD`, `BACKTEST:BTCUSDT`, `BYBIT_SPOT:BTCUSDT`
            (r"([A-Z_][A-Z0-9_]*)(:)([A-Z0-9_]+)",
             bygroups(Name.Class, Punctuation, Name.Constant)),

            # stream-field access: `btc.close`, `gold.high`, `eur.volume`
            (r"([a-zA-Z_][\w]*)(\.)([a-zA-Z_]\w*)",
             bygroups(Name.Variable, Punctuation, Name.Attribute)),

            # indicator/function calls
            (r"(" + r"|".join(INDICATOR_FUNCS) + r")(\s*)(\()",
             bygroups(Name.Function, Text, Punctuation)),

            # generic function call (lowercase-ident followed by `(`)
            (r"([a-z_][\w]*)(\s*)(\()",
             bygroups(Name.Function, Text, Punctuation)),

            # named time-window: `1m`, `15m`, `1h`, `1d`, `5s`
            (r"\b\d+[smhdwM]\b", Number.Integer),

            # numbers
            (r"\b\d+\.\d+([eE][+-]?\d+)?\b", Number.Float),
            (r"\b\d+([eE][+-]?\d+)?\b", Number.Integer),

            # strings
            (r'"([^"\\]|\\.)*"', String.Double),
            (r"'([^'\\]|\\.)*'", String.Single),

            # placeholders inside LOG strings: `{name}` — caught after strings
            # but useful as a separate run too
            (r"\{[a-zA-Z_][\w]*\}", String.Interpol),

            # operators
            (r"(==|!=|<=|>=|<|>|=|\+|-|\*|/|%)", Operator),

            # punctuation
            (r"[\[\](){},;:]", Punctuation),

            # identifiers
            (r"[A-Z_][A-Z0-9_]+\b", Name.Constant),     # upper-case constant
            (r"[a-zA-Z_][\w]*", Name),
        ],
        "whitespace": [
            (r"\s+", Text),
        ],
        "comments": [
            (r"//[^\n]*", Comment.Single),
            (r"#[^\n]*", Comment.Single),
            (r"/\*", Comment.Multiline, "block_comment"),
        ],
        "block_comment": [
            (r"[^*/]+", Comment.Multiline),
            (r"\*/", Comment.Multiline, "#pop"),
            (r"[*/]", Comment.Multiline),
        ],
    }
