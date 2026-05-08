package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.Ref

const val SYMBOL_PLACEHOLDER_NAME = "__SYMBOL__"

val SYMBOL: ExprAst = Ref(SYMBOL_PLACEHOLDER_NAME)
