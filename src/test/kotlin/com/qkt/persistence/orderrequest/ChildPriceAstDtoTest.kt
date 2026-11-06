package com.qkt.persistence.orderrequest

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StackEntryRef
import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChildPriceAstDtoTest {
    private val entryPlusTwo = BinaryOp(BinOp.ADD, StackEntryRef, NumLit(BigDecimal("2")))

    private fun roundTrip(ast: ChildPriceAst): ChildPriceAst {
        val json = Json.encodeToString(ChildPriceAstDto.fromDomain(ast))
        return Json.decodeFromString<ChildPriceAstDto>(json).toDomain()
    }

    @Test
    fun `every child price variant survives a json round trip`() {
        val variants =
            listOf(
                ChildAt(entryPlusTwo),
                ChildBy(NumLit(BigDecimal("3.5"))),
                ChildPct(NumLit(BigDecimal("1"))),
                ChildRr(BinaryOp(BinOp.MUL, NumLit(BigDecimal("2")), NumLit(BigDecimal("1.5")))),
                ChildArmedTrail(NumLit(BigDecimal("4")), BinaryOp(BinOp.SUB, StackEntryRef, StackEntryRef)),
            )
        variants.forEach { assertThat(roundTrip(it)).isEqualTo(it) }
    }

    @Test
    fun `child price types are written under their persisted names`() {
        val one = NumLit(BigDecimal.ONE)
        assertThat(ChildPriceAstDto.fromDomain(ChildAt(one)).type).isEqualTo("At")
        assertThat(ChildPriceAstDto.fromDomain(ChildBy(one)).type).isEqualTo("By")
        assertThat(ChildPriceAstDto.fromDomain(ChildPct(one)).type).isEqualTo("Pct")
        assertThat(ChildPriceAstDto.fromDomain(ChildRr(one)).type).isEqualTo("Rr")
        val trail = ChildPriceAstDto.fromDomain(ChildArmedTrail(one, StackEntryRef))
        assertThat(trail.type).isEqualTo("ArmedTrail")
        assertThat(trail.second).isEqualTo(FillAnchorExprDto("Entry"))
    }

    @Test
    fun `an armed trail without its threshold is rejected`() {
        val dto = ChildPriceAstDto("ArmedTrail", FillAnchorExprDto("NumLit", value = "1"))
        assertThatThrownBy { dto.toDomain() }.hasMessageContaining("missing mfeThreshold")
    }

    @Test
    fun `an unknown child price type is rejected by name`() {
        val dto = ChildPriceAstDto("Sideways", FillAnchorExprDto("Entry"))
        assertThatThrownBy { dto.toDomain() }.hasMessageContaining("Sideways")
    }

    @Test
    fun `a fill anchor binary op restores its operator and operands`() {
        val dto = FillAnchorExprDto.fromDomain(entryPlusTwo)
        assertThat(dto).isEqualTo(
            FillAnchorExprDto(
                type = "BinaryOp",
                op = "ADD",
                lhs = FillAnchorExprDto("Entry"),
                rhs = FillAnchorExprDto("NumLit", value = "2"),
            ),
        )
        assertThat(dto.toDomain()).isEqualTo(entryPlusTwo)
    }

    @Test
    fun `fill anchor dtos name the field they are missing`() {
        val num = FillAnchorExprDto("NumLit", value = "1")
        val cases =
            mapOf(
                FillAnchorExprDto("NumLit") to "NumLit fill-anchor DTO missing value",
                FillAnchorExprDto("BinaryOp") to "missing op",
                FillAnchorExprDto("BinaryOp", op = "ADD") to "missing lhs",
                FillAnchorExprDto("BinaryOp", op = "ADD", lhs = num) to "missing rhs",
                FillAnchorExprDto("Mystery") to "Mystery",
            )
        cases.forEach { (dto, message) ->
            assertThatThrownBy { dto.toDomain() }.hasMessageContaining(message)
        }
    }

    @Test
    fun `a fill anchor outside literals entry and arithmetic cannot be persisted`() {
        assertThatThrownBy { FillAnchorExprDto.fromDomain(Ref("atr")) }
            .hasMessageContaining("Unsupported persisted fill-anchor expression Ref")
    }
}
