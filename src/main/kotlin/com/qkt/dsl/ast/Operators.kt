package com.qkt.dsl.ast

enum class BinOp { ADD, SUB, MUL, DIV, AND, OR }

enum class UnOp { NEG, NOT }

enum class Cmp { GT, LT, GE, LE, EQ, NE }

enum class CrossDir { ABOVE, BELOW }

enum class AggFn { MIN, MAX, MEAN, SUM }

enum class StateSource {
    ACCOUNT,
    POSITION,
    POSITION_AVG_PRICE,
    POSITION_PNL,
    POSITION_REALIZED_PNL,
    POSITION_UNREALIZED_PNL,
    POSITION_HOLDING_DURATION,
    POSITION_MFE,
    OPEN_ORDERS,
}
