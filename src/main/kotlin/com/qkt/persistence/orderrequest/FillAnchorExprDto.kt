package com.qkt.persistence.orderrequest

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackEntryRef
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/**
 * On-disk shape of a fill-anchored expression inside a child price: a number literal, the
 * entry reference, or a binary operation over two such expressions.
 */
@Serializable
internal data class FillAnchorExprDto(
    val type: String,
    val value: String? = null,
    val op: String? = null,
    val lhs: FillAnchorExprDto? = null,
    val rhs: FillAnchorExprDto? = null,
) {
    fun toDomain(): ExprAst =
        when (type) {
            "NumLit" ->
                NumLit(
                    BigDecimal(requireNotNull(value) { "NumLit fill-anchor DTO missing value" }),
                )
            "Entry" -> StackEntryRef
            "BinaryOp" ->
                BinaryOp(
                    op =
                        BinOp.valueOf(
                            requireNotNull(op) { "BinaryOp fill-anchor DTO missing op" },
                        ),
                    lhs = requireNotNull(lhs) { "BinaryOp fill-anchor DTO missing lhs" }.toDomain(),
                    rhs = requireNotNull(rhs) { "BinaryOp fill-anchor DTO missing rhs" }.toDomain(),
                )
            else -> error("Unknown fill-anchor expression type in persisted state: $type")
        }

    companion object {
        fun fromDomain(value: ExprAst): FillAnchorExprDto =
            when (value) {
                is NumLit ->
                    FillAnchorExprDto("NumLit", value = value.value.toPlainString())
                StackEntryRef -> FillAnchorExprDto("Entry")
                is BinaryOp ->
                    FillAnchorExprDto(
                        type = "BinaryOp",
                        op = value.op.name,
                        lhs = fromDomain(value.lhs),
                        rhs = fromDomain(value.rhs),
                    )
                else ->
                    error(
                        "Unsupported persisted fill-anchor expression ${value::class.simpleName}",
                    )
            }
    }
}
