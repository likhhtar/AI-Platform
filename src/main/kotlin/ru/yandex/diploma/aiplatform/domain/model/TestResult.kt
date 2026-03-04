package ru.yandex.diploma.aiplatform.domain.model

data class TestResult(
    val testCase: TestCase,
    val success: Boolean,
    val evaluationResult: EvaluationResult,
    val metrics: Map<String, MetricResult> = emptyMap(),
    val llmResponse: LlmResponse?,
    val executionTimeMs: Long,
    val error: String?,
    val infrastructureError: Boolean = false,
    val provider: String? = null,
    
    val baselineResult: EvaluationResult? = null,
    val optimizedResult: EvaluationResult? = null,
    val improvement: Double? = null,
    
    val qaAnalysis: QAAnalysis? = null
) {
    init {
        require(evaluationResult.score >= 0.0 && evaluationResult.score <= 1.0) {
            "Evaluation score must be between 0.0 and 1.0, got ${evaluationResult.score}"
        }
        
        baselineResult?.let { baseline ->
            require(baseline.score >= 0.0 && baseline.score <= 1.0) {
                "Baseline score must be between 0.0 and 1.0, got ${baseline.score}"
            }
        }
        
        optimizedResult?.let { optimized ->
            require(optimized.score >= 0.0 && optimized.score <= 1.0) {
                "Optimized score must be between 0.0 and 1.0, got ${optimized.score}"
            }
        }
    }
    
    val hasOptimizationData: Boolean
        get() = baselineResult != null && optimizedResult != null
    
    val isImproved: Boolean
        get() = improvement != null && improvement > 0.0

    val isSuspicious: Boolean
        get() = qaAnalysis?.isSuspicious == true
}


data class QAAnalysis(
    val category: TestCategory,
    val flags: List<String> = emptyList(),
    val isSuspicious: Boolean = false,
    val confidence: Double = 1.0,
    val reasoning: String? = null
) {
    init {
        require(confidence >= 0.0 && confidence <= 1.0) {
            "Confidence must be between 0.0 and 1.0, got $confidence"
        }
    }
}

enum class TestCategory {
    BASELINE,
    PARAPHRASE,
    TRAP,
    UNSEEN,
    MISLEADING,
    EDGE_CASE
}

data class MetricResult(
    val name: String,
    val score: Double,
    val explanation: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val isNormalized: Boolean = true
) {
    init {
        if (isNormalized) {
            require(score >= 0.0 && score <= 1.0) {
                "Normalized score must be between 0.0 and 1.0, got $score"
            }
        }
    }
}