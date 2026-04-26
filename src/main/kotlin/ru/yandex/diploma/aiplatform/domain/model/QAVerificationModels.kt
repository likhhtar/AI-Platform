package ru.yandex.diploma.aiplatform.domain.model

data class QAVerificationResult(
    val isLegitimate: Boolean,
    val redFlags: List<RedFlag>,
    val metrics: QAVerificationMetrics,
    val recommendation: String,
    val verificationStatus: VerificationStatus,
    val timestamp: String = java.time.Instant.now().toString()
)

data class QAVerificationMetrics(
    val baselineAccuracy: Double,
    val optimizedAccuracy: Double,
    val paraphraseAccuracy: Double,
    val trapAccuracy: Double,
    val unseenAccuracy: Double,
    val misleadingAccuracy: Double,
    val performanceDropOnUnseen: Double,
    val consistencyScore: Double,
    val generalizationScore: Double,
    val suspiciousPatternCount: Int,
    val categoryBreakdown: Map<String, CategoryMetrics>
)

data class CategoryMetrics(
    val category: String,
    val baselineAccuracy: Double,
    val optimizedAccuracy: Double,
    val improvement: Double,
    val testCount: Int,
    val status: CategoryStatus
)

data class RedFlag(
    val type: RedFlagType,
    val severity: Severity,
    val description: String,
    val evidence: String,
    val confidence: Double,
    val recommendation: String
)

enum class RedFlagType {
    HARDCODED_ANSWERS,
    OVERFITTING,
    SUSPICIOUS_PATTERNS,
    PERFORMANCE_ANOMALY,
    PROMPT_LEAKAGE,
    INCONSISTENT_OPTIMIZATION
}

enum class Severity {
    LOW, MEDIUM, HIGH, CRITICAL
}

enum class VerificationStatus {
    LEGITIMATE,
    SUSPICIOUS,
    FAILED,
    INCONCLUSIVE
}

enum class CategoryStatus {
    GOOD,
    WARNING,
    CRITICAL
}

data class ExtendedOptimizationExperimentResult(
    val baselineResult: ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult,
    val optimizationResult: OptimizationResult,
    val optimizedExperimentResult: ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult?,
    val improvement: OptimizationImprovement?,
    val config: OptimizationConfig,
    val qaVerification: QAVerificationResult,
    val executionTimeMs: Long,
    val timestamp: String = java.time.Instant.now().toString()
)