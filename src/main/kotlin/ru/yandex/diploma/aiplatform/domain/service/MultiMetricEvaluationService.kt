package ru.yandex.diploma.aiplatform.domain.service

import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.evaluator.*
import ru.yandex.diploma.aiplatform.domain.model.*

@Service
class MultiMetricEvaluationService(
    private val metricRegistry: MetricRegistry
) {
    
    suspend fun evaluateAllMetrics(
        output: String,
        expected: String,
        testCase: TestCaseWithMetrics,
        llmResponse: LlmResponse?,
        executionTimeMs: Long
    ): Map<String, MetricResult> {
        val context = EvaluationContext(
            testCase = testCase.toTestCase(),
            llmResponse = llmResponse,
            executionTimeMs = executionTimeMs,
            metadata = testCase.metadata
        )
        
        return testCase.metrics.associate { metricDef ->
            val evaluator = metricRegistry.getMetric(metricDef.evaluatorType)
                ?: throw IllegalArgumentException("Unknown metric evaluator: ${metricDef.evaluatorType}")
            
            val result = evaluator.evaluate(output, expected, context)
            
            val adjustedResult = applyMetricConfiguration(result, metricDef)
            
            metricDef.name to adjustedResult
        }
    }
    
    private fun applyMetricConfiguration(
        result: MetricResult,
        definition: MetricDefinition
    ): MetricResult {
        val passed = definition.threshold?.let { threshold ->
            result.score >= threshold
        }
        
        val additionalMetadata = mutableMapOf<String, Any>()
        additionalMetadata["weight"] = definition.weight
        definition.threshold?.let { additionalMetadata["threshold"] = it }
        passed?.let { additionalMetadata["passed"] = it }
        
        return result.copy(
            metadata = result.metadata + additionalMetadata
        )
    }
}