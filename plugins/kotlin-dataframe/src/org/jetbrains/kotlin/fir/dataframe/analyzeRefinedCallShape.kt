/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.dataframe

import org.jetbrains.kotlin.fir.dataframe.Names.DF_CLASS_ID
import org.jetbrains.kotlin.fir.dataframe.api.CreateDataFrameConfiguration
import org.jetbrains.kotlin.fir.dataframe.api.TraverseConfiguration
import org.jetbrains.kotlin.fir.dataframe.api.toDataFrame
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlinx.dataframe.KotlinTypeFacade
import org.jetbrains.kotlinx.dataframe.annotations.Interpreter
import org.jetbrains.kotlinx.dataframe.plugin.PluginDataFrameSchema

fun KotlinTypeFacade.analyzeRefinedCallShape(call: FirFunctionCall, reporter: InterpretationErrorReporter): CallResult? {
    val callReturnType = call.resolvedType
    if (callReturnType.classId != DF_CLASS_ID) return null
    val rootMarker = callReturnType.typeArguments[0]
    // rootMarker is expected to be a token generated by the plugin.
    // it's implied by "refined call"
    // thus ConeClassLikeType
    if (rootMarker !is ConeClassLikeType) {
        return null
    }

    val dataFrameSchema: PluginDataFrameSchema = call.interpreterName(session)?.let {
        when (it) {
            "toDataFrameDsl" -> {
                val list = call.argumentList as FirResolvedArgumentList
                val lambda = (list.arguments.singleOrNull() as? FirAnonymousFunctionExpression)?.anonymousFunction
                val statements = lambda?.body?.statements
                if (statements != null) {
                    val receiver = CreateDataFrameConfiguration()
                    statements.filterIsInstance<FirFunctionCall>().forEach { call ->
                        val schemaProcessor = call.loadInterpreter() ?: return@forEach
                        interpret(
                            call,
                            schemaProcessor,
                            mapOf("dsl" to Interpreter.Success(receiver)),
                            reporter
                        )
                    }
                    toDataFrame(receiver.maxDepth, call, receiver.traverseConfiguration)
                } else {
                    PluginDataFrameSchema(emptyList())
                }
            }
            "toDataFrame" -> {
                val list = call.argumentList as FirResolvedArgumentList
                val argument = list.mapping.entries.firstOrNull { it.value.name == Name.identifier("maxDepth") }?.key
                val maxDepth = when (argument) {
                    null -> 0
                    is FirLiteralExpression -> (argument.value as Number).toInt()
                    else -> null
                }
                if (maxDepth != null) {
                    toDataFrame(maxDepth, call, TraverseConfiguration())
                } else {
                    PluginDataFrameSchema(emptyList())
                }
            }
            "toDataFrameDefault" -> {
                val maxDepth = 0
                toDataFrame(maxDepth, call, TraverseConfiguration())
            }
            else -> it.load<Interpreter<*>>().let { processor ->
                val dataFrameSchema = interpret(call, processor, reporter = reporter)
                    .let {
                        val value = it?.value
                        if (value !is PluginDataFrameSchema) {
                            if (!reporter.errorReported) {
                                reporter.reportInterpretationError(call, "${processor::class} must return ${PluginDataFrameSchema::class}, but was ${value}")
                            }
                            return null
                        }
                        value
                    }
                dataFrameSchema
            }
        }
    } ?: return null

    return CallResult(rootMarker, dataFrameSchema)
}

data class CallResult(val rootMarker: ConeClassLikeType, val dataFrameSchema: PluginDataFrameSchema)

class Arguments(val refinedArguments: List<RefinedArgument>) : List<RefinedArgument> by refinedArguments

data class RefinedArgument(val name: Name, val expression: FirExpression) {

    override fun toString(): String {
        return "RefinedArgument(name=$name, expression=${expression})"
    }
}

data class SchemaProperty(
    val marker: ConeTypeProjection,
    val name: String,
    val dataRowReturnType: ConeKotlinType,
    val columnContainerReturnType: ConeKotlinType,
    val override: Boolean = false
)
