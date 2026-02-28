package ru.yandex.diploma.aiplatform.domain.evaluator

import ru.yandex.diploma.aiplatform.domain.model.*

interface MetricEvaluator {
    val name: String
    val type: MetricType
    
    suspend fun evaluate(
        output: String,
        expected: String,
        context: EvaluationContext
    ): MetricResult
    
    fun getConfigSchema(): Map<String, Any> = emptyMap()
}

interface MetricRegistry {
    fun registerMetric(evaluator: MetricEvaluator)
    fun getMetric(name: String): MetricEvaluator?
    fun getAllMetrics(): List<MetricEvaluator>
    fun getMetricsByType(type: MetricType): List<MetricEvaluator>
}