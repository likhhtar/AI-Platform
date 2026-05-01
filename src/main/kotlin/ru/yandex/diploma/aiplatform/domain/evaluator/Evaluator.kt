package ru.yandex.diploma.aiplatform.domain.evaluator

import ru.yandex.diploma.aiplatform.domain.model.EvaluationResult

interface Evaluator {
    val evaluatorType: String

    suspend fun evaluate(
        output: String,
        expected: String,
        metadata: Map<String, Any> = emptyMap(),
    ): EvaluationResult
}

interface EvaluatorRegistry {
    fun register(evaluator: Evaluator)
    
    fun getEvaluator(type: String): Evaluator
    
    fun getAvailableTypes(): Set<String>
}

class EvaluatorNotFoundException(
    evaluatorType: String
) : Exception("Evaluator not found: $evaluatorType")