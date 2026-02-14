package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.evaluator.*
import ru.yandex.diploma.aiplatform.domain.model.*
import java.util.concurrent.ConcurrentHashMap

@Component
class DefaultMetricRegistry : MetricRegistry {
    private val metrics = ConcurrentHashMap<String, MetricEvaluator>()
    
    init {
        registerBuiltInMetrics()
    }
    
    private fun registerBuiltInMetrics() {
        registerMetric(CorrectnessMetricEvaluator())
        registerMetric(LatencyMetricEvaluator())
        registerMetric(TokenUsageMetricEvaluator())
    }
    
    override fun registerMetric(evaluator: MetricEvaluator) {
        metrics[evaluator.name] = evaluator
    }
    
    override fun getMetric(name: String): MetricEvaluator? = metrics[name]
    
    override fun getAllMetrics(): List<MetricEvaluator> = metrics.values.toList()
    
    override fun getMetricsByType(type: MetricType): List<MetricEvaluator> = 
        metrics.values.filter { it.type == type }
}

class CorrectnessMetricEvaluator : MetricEvaluator {
    
    override val name = "correctness"
    override val type = MetricType.CORRECTNESS
    
    override suspend fun evaluate(
        output: String,
        expected: String,
        context: EvaluationContext
    ): MetricResult {
        val score = if (output.trim().equals(expected.trim(), ignoreCase = true)) 1.0 else 0.0
        
        return MetricResult(
            name = name,
            score = score,
            explanation = if (score == 1.0) "Exact match" else "No match",
            metadata = mapOf(
                "output" to output,
                "expected" to expected
            ),
            isNormalized = true
        )
    }
}

class LatencyMetricEvaluator : MetricEvaluator {
    
    override val name = "latency"
    override val type = MetricType.LATENCY
    
    override suspend fun evaluate(
        output: String,
        expected: String,
        context: EvaluationContext
    ): MetricResult {
        val latencyMs = context.executionTimeMs
        val maxLatencyMs = context.metadata["maxLatencyMs"] as? Long ?: 10000L
        
        val normalizedScore = when {
            latencyMs <= maxLatencyMs / 2 -> 1.0
            latencyMs <= maxLatencyMs -> 1.0 - (latencyMs - maxLatencyMs / 2).toDouble() / (maxLatencyMs / 2)
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        
        return MetricResult(
            name = name,
            score = normalizedScore,
            explanation = "Response time: ${latencyMs}ms (target: <${maxLatencyMs}ms)",
            metadata = mapOf(
                "latencyMs" to latencyMs,
                "maxLatencyMs" to maxLatencyMs,
                "rawLatency" to latencyMs
            ),
            isNormalized = true
        )
    }
    
    override fun getConfigSchema(): Map<String, Any> = mapOf(
        "maxLatencyMs" to mapOf(
            "type" to "integer",
            "default" to 10000,
            "description" to "Maximum acceptable latency in milliseconds"
        )
    )
}

class TokenUsageMetricEvaluator : MetricEvaluator {
    
    override val name = "token_usage"
    override val type = MetricType.TOKEN_USAGE
    
    override suspend fun evaluate(
        output: String,
        expected: String,
        context: EvaluationContext
    ): MetricResult {
        val tokensUsed = context.llmResponse?.tokensUsed ?: 0
        val maxTokens = context.metadata["maxTokens"] as? Int ?: 1000
        
        val normalizedScore = when {
            tokensUsed <= maxTokens / 2 -> 1.0
            tokensUsed <= maxTokens -> 1.0 - (tokensUsed - maxTokens / 2).toDouble() / (maxTokens / 2)
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        
        return MetricResult(
            name = name,
            score = normalizedScore,
            explanation = "Tokens used: $tokensUsed (target: <$maxTokens)",
            metadata = mapOf(
                "tokensUsed" to tokensUsed,
                "maxTokens" to maxTokens
            ),
            isNormalized = true
        )
    }
    
    override fun getConfigSchema(): Map<String, Any> = mapOf(
        "maxTokens" to mapOf(
            "type" to "integer",
            "default" to 1000,
            "description" to "Maximum acceptable token usage"
        )
    )
}