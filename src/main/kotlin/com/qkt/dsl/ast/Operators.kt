package com.qkt.dsl.ast

enum class BinOp { ADD, SUB, MUL, DIV, AND, OR }

enum class UnOp { NEG, NOT }

enum class Cmp { GT, LT, GE, LE, EQ, NE }

enum class CrossDir { ABOVE, BELOW }

enum class AggFn { MIN, MAX, MEAN, SUM }

enum class StateSource { ACCOUNT, POSITION, POSITION_AVG_PRICE, OPEN_ORDERS }
