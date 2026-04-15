package ru.yandex.diploma.aiplatform.domain.model

import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import java.time.Instant

data class OptimizationConfig(
    val enabled: Boolean = false,
    val mode: OptimizationMode = OptimizationMode.SUGGEST,
    val type: OptimizerType = OptimizerType.LLM,
    val iterations: Int = 1,
    val llmConfig: LlmOptimizerConfig? = null,
    val ruleBasedConfig: RuleBasedOptimizerConfig? = null
) {
    init {
        require(iterations > 0) { "Iterations must be positive" }
        if (enabled && type == OptimizerType.LLM) {
            requireNotNull(llmConfig) { "LLM config is required when using LLM optimizer" }
        }
    }
}

data class LlmOptimizerConfig(
    val provider: String,
    val model: String,
    val temperature: Double = 0.3,
    val maxTokens: Int? = null,
    val systemPrompt: String? = null
)

data class RuleBasedOptimizerConfig(
    val rules: List<OptimizationRule> = emptyList(),
    val enableLengthOptimization: Boolean = true,
    val enableClarityOptimization: Boolean = true,
    val enableSpecificityOptimization: Boolean = true
)

data class OptimizationRule(
    val name: String,
    val description: String,
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true
)

enum class OptimizationMode {
    SUGGEST,
    APPLY
}

enum class OptimizerType {
    LLM,
    RULE_BASED
}

data class OptimizationInput(
    val originalPrompt: Prompt,
    val testCases: List<TestCase>,
    val testResults: List<TestResult>,
    val agentConfig: AgentConfig
) {
    init {
        require(testCases.isNotEmpty()) { "Test cases cannot be empty" }
        require(testResults.isNotEmpty()) { "Test results cannot be empty" }
    }
}

data class OptimizationResult(
    val originalPrompt: Prompt,
    val optimizedPrompt: Prompt?,
    val suggestions: List<OptimizationSuggestion>,
    val confidence: Double,
    val reasoning: String,
    val metadata: Map<String, Any> = emptyMap(),
    val executionTimeMs: Long,
    val timestamp: String = Instant.now().toString()
) {
    init {
        require(confidence >= 0.0 && confidence <= 1.0) { "Confidence must be between 0.0 and 1.0" }
    }
}

data class OptimizationSuggestion(
    val type: SuggestionType,
    val description: String,
    val originalText: String?,
    val suggestedText: String?,
    val impact: SuggestionImpact,
    val confidence: Double,
    val reasoning: String
) {
    init {
        require(confidence >= 0.0 && confidence <= 1.0) { "Confidence must be between 0.0 and 1.0" }
    }
}

enum class SuggestionType {
    CLARITY,
    SPECIFICITY,
    LENGTH,
    STRUCTURE,
    CONTEXT,
    EXAMPLES,
    CONSTRAINTS,
    FORMAT,
    TONE,
    OTHER
}

enum class SuggestionImpact {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class OptimizationExperimentResult(
    val baselineResult: ExperimentResult,
    val optimizationResult: OptimizationResult,
    val optimizedExperimentResult: ExperimentResult?,
    val improvement: OptimizationImprovement?,
    val config: OptimizationConfig,
    val executionTimeMs: Long,
    val timestamp: String = Instant.now().toString()
)

data class OptimizationImprovement(
    val scoreImprovement: Double,
    val latencyChange: Double,
    val passRateImprovement: Double,
    val significantImprovement: Boolean,
    val detailedMetrics: Map<String, Double> = emptyMap()
)