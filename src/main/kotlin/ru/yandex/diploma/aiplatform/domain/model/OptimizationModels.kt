package ru.yandex.diploma.aiplatform.domain.model

import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import java.time.Instant

data class OptimizationConfig(
    val enabled: Boolean = false,
    val mode: OptimizationMode = OptimizationMode.SUGGEST,
    val type: OptimizerType = OptimizerType.LLM,
    val iterations: Int = 1,
    val llmConfig: LlmOptimizerConfig? = null,
    val ruleBasedConfig: RuleBasedOptimizerConfig? = null,
    val plateauScoreEpsilon: Double = 0.008,
    val rollbackMedianThreshold: Double = 0.015,
    val mutationPrompt: String =
        "Rewrite this instruction to be clearer and more specific",
    val evolveMutationPrompt: Boolean = false,
    val mutationEvolveEveryNIterations: Int = 3,
    val useLineage: Boolean = true,
    val useLamarckian: Boolean = true,
    val useEda: Boolean = false,
    val useTextGrad: Boolean = false,
) {
    init {
        require(iterations > 0) { "Iterations must be positive" }
        require(plateauScoreEpsilon >= 0.0) { "plateauScoreEpsilon must be non-negative" }
        require(rollbackMedianThreshold >= 0.0) { "rollbackMedianThreshold must be non-negative" }
        require(mutationEvolveEveryNIterations > 0) {
            "mutationEvolveEveryNIterations must be positive"
        }
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

enum class OptimizationStatus {
    SUGGESTED,

    APPLIED,

    ROLLED_BACK,

    FAILED,
}

data class OptimizationAuditEvent(
    val iteration: Int?,
    val optimizerType: OptimizerType,
    val status: OptimizationStatus,
    val scoreDelta: Double?,
    val medianDelta: Double?,
    val passRateDelta: Double?,
    val cycleDetected: Boolean,
    val plateauDetected: Boolean,
    val rollbackReason: String?,
)

data class OptimizationInput(
    val originalPrompt: Prompt,
    val testCases: List<TestCase>,
    val testResults: List<TestResult>,
    val agentConfig: AgentConfig,
    val metaPromptOverride: String? = null,
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
    val timestamp: String = Instant.now().toString(),
    val status: OptimizationStatus = OptimizationStatus.SUGGESTED,
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

data class OptimizationImprovement(
    val scoreImprovement: Double,
    val latencyChange: Double,
    val passRateImprovement: Double,
    val significantImprovement: Boolean,
    val detailedMetrics: Map<String, Double> = emptyMap(),
    val medianScoreImprovement: Double = 0.0,
    val perTestScoreDelta: Map<String, Double> = emptyMap(),
    val regressionDetected: Boolean = false,
    val rolledBack: Boolean = false,
    val rollbackReason: String? = null,
)

fun deriveOptimizationStatus(
    mode: OptimizationMode,
    optimizedPrompt: Prompt?,
    improvement: OptimizationImprovement?,
    harnessEvaluationFailed: Boolean,
): OptimizationStatus {
    if (mode == OptimizationMode.SUGGEST) return OptimizationStatus.SUGGESTED
    if (harnessEvaluationFailed) return OptimizationStatus.FAILED
    if (improvement == null) return OptimizationStatus.FAILED
    if (improvement.rolledBack) return OptimizationStatus.ROLLED_BACK
    return if (optimizedPrompt != null) OptimizationStatus.APPLIED else OptimizationStatus.FAILED
}

data class OptimizationIterationSummary(
    val round: Int,
    val improvement: OptimizationImprovement?,
    val haltedDueToPlateau: Boolean = false,
    val haltedDueToCycle: Boolean = false,
)

data class OptimizationIterationReportRow(
    val iteration: Int,
    val proposedPromptTemplate: String,
    val scoreBefore: Double?,
    val scoreAfter: Double?,
    val rolledBack: Boolean,
    val rollbackReason: String?,
)

data class OptimizationExperimentResult(
    val baselineResult: ExperimentResult,
    val optimizationResult: OptimizationResult,
    val optimizedExperimentResult: ExperimentResult?,
    val improvement: OptimizationImprovement?,
    val config: OptimizationConfig,
    val executionTimeMs: Long,
    val timestamp: String = Instant.now().toString(),
    val iterationSummaries: List<OptimizationIterationSummary> = emptyList(),
    val iterationReportRows: List<OptimizationIterationReportRow> = emptyList(),
)

data class PromptVersion(
    val mutationPrompt: String? = null,
    val iteration: Int? = null,
    val prompt: String? = null,
    val score: Double? = null,
)

data class JudgeExplanation(
    val explanation: String,
    val score: Double,
)