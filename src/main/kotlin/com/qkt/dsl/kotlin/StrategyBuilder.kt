package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ConstantDecl
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

@DslMarker
annotation class QktDsl

@QktDsl
class StrategyBuilder(
    private val name: String,
    private val version: Int,
) {
    private val streams: MutableList<StreamDecl> = mutableListOf()
    private val constants: MutableList<ConstantDecl> = mutableListOf()
    internal val lets: MutableList<LetDecl> = mutableListOf()
    private val rules: MutableList<RuleAst> = mutableListOf()

    fun stream(
        alias: String,
        broker: String,
        symbol: String,
        every: String,
    ): StreamRef {
        streams.add(StreamDecl(alias = alias, broker = broker, symbol = symbol, timeframe = every))
        return StreamRef(alias = alias)
    }

    fun letting(expr: ExprAst): LetBindingProvider = LetBindingProvider(this, expr)

    fun rule(block: RuleBuilder.() -> Unit) {
        val rb = RuleBuilder()
        rb.block()
        addRule(rb.build())
    }

    internal fun addRule(rule: RuleAst) {
        rules.add(rule)
    }

    internal fun build(): StrategyAst =
        StrategyAst(
            name = name,
            version = version,
            streams = streams.toList(),
            constants = constants.toList(),
            lets = lets.toList(),
            defaults = null,
            rules = rules.toList(),
        )
}

@QktDsl
class RuleBuilder {
    private var cond: ExprAst? = null
    private var action: ActionAst? = null

    fun whenever(c: ExprAst) {
        cond = c
    }

    fun then(block: ActionScope.() -> ActionAst) {
        action = ActionScope.block()
    }

    internal fun build(): WhenThen {
        val c = cond ?: error("rule { ... } missing whenever(...)")
        val a = action ?: error("rule { ... } missing then { ... }")
        return WhenThen(c, a)
    }
}

class LetBindingProvider(
    private val builder: StrategyBuilder,
    private val expr: ExprAst,
) {
    operator fun provideDelegate(
        thisRef: Any?,
        property: KProperty<*>,
    ): ReadOnlyProperty<Any?, ExprAst> {
        builder.lets.add(LetDecl(property.name, expr))
        val ref = Ref(property.name)
        return ReadOnlyProperty { _, _ -> ref }
    }
}

fun strategy(
    name: String,
    version: Int,
    block: StrategyBuilder.() -> Unit,
): StrategyAst {
    val b = StrategyBuilder(name = name, version = version)
    b.block()
    return b.build()
}
