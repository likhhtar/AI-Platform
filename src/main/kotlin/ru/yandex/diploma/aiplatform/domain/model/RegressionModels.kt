package ru.yandex.diploma.aiplatform.domain.model

import java.time.Instant
import kotlin.math.abs

data class RegressionRule(
    val metricName: String,
    val threshold: Double,
    val type: RegressionType = RegressionType.RELATIVE,
    val severity: RegressionSeverity = RegressionSeverity.WARNING,
    val description: String = "",
    val direction: MetricDirection = MetricDirections.forMetric(metricName)
) {
    init {
        require(metricName.isNotBlank()) { "Metric name cannot be blank" }
        require(threshold >= 0.0) { "Threshold must be non-negative" }
    }
}

enum class RegressionType {
    ABSOLUTE,
    RELATIVE,
    PERCENTAGE
}

enum class RegressionSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

data class RegressionResult(
    val metricName: String,
    val currentValue: Double,
    val baselineValue: Double,
    val delta: Double,
    val relativeDelta: Double,
    val percentageDelta: Double,
    val rule: RegressionRule,
    val isRegression: Boolean,
    val severity: RegressionSeverity,
    val explanation: String
) {
    companion object {
        fun calculate(
            metricName: String,
            currentValue: Double,
            baselineValue: Double,
            rule: RegressionRule
        ): RegressionResult {
            val delta = currentValue - baselineValue
            val relativeDelta = if (baselineValue != 0.0) delta / baselineValue else 0.0
            val percentageDelta = relativeDelta * 100.0
            
            val isRegression = isWorsening(
                rule = rule,
                baselineValue = baselineValue,
                currentValue = currentValue,
                delta = delta,
                relativeDelta = relativeDelta,
                percentageDelta = percentageDelta
            )
            
            val explanation = buildExplanation(
                metricName, currentValue, baselineValue, delta, 
                relativeDelta, percentageDelta, rule, isRegression
            )
            
            return RegressionResult(
                metricName = metricName,
                currentValue = currentValue,
                baselineValue = baselineValue,
                delta = delta,
                relativeDelta = relativeDelta,
                percentageDelta = percentageDelta,
                rule = rule,
                isRegression = isRegression,
                severity = if (isRegression) rule.severity else RegressionSeverity.INFO,
                explanation = explanation
            )
        }

        private fun isWorsening(
            rule: RegressionRule,
            baselineValue: Double,
            currentValue: Double,
            delta: Double,
            relativeDelta: Double,
            percentageDelta: Double
        ): Boolean {
            val baselineZero = baselineValue == 0.0
            return when (rule.type) {
                RegressionType.ABSOLUTE -> when (rule.direction) {
                    MetricDirection.HIGHER_IS_BETTER ->
                        (baselineValue - currentValue) > rule.threshold
                    MetricDirection.LOWER_IS_BETTER ->
                        (currentValue - baselineValue) > rule.threshold
                }
                RegressionType.RELATIVE -> when {
                    baselineZero -> when (rule.direction) {
                        MetricDirection.HIGHER_IS_BETTER ->
                            (baselineValue - currentValue) > rule.threshold
                        MetricDirection.LOWER_IS_BETTER ->
                            (currentValue - baselineValue) > rule.threshold
                    }
                    else -> when (rule.direction) {
                        MetricDirection.HIGHER_IS_BETTER ->
                            -relativeDelta > rule.threshold
                        MetricDirection.LOWER_IS_BETTER ->
                            relativeDelta > rule.threshold
                    }
                }
                RegressionType.PERCENTAGE -> when {
                    baselineZero -> when (rule.direction) {
                        MetricDirection.HIGHER_IS_BETTER ->
                            (baselineValue - currentValue) > rule.threshold
                        MetricDirection.LOWER_IS_BETTER ->
                            (currentValue - baselineValue) > rule.threshold
                    }
                    else -> when (rule.direction) {
                        MetricDirection.HIGHER_IS_BETTER ->
                            -percentageDelta > rule.threshold
                        MetricDirection.LOWER_IS_BETTER ->
                            percentageDelta > rule.threshold
                    }
                }
            }
        }
        
        private fun buildExplanation(
            metricName: String,
            current: Double,
            baseline: Double,
            delta: Double,
            relativeDelta: Double,
            percentageDelta: Double,
            rule: RegressionRule,
            isRegression: Boolean
        ): String {
            val moved = if (delta > 0) "increased" else "decreased"
            val absPercentage = abs(percentageDelta)
            val directionHint = when (rule.direction) {
                MetricDirection.HIGHER_IS_BETTER -> "higher is better"
                MetricDirection.LOWER_IS_BETTER -> "lower is better"
            }
            
            return if (isRegression) {
                "REGRESSION: $metricName worsened ($directionHint): $moved from $baseline to $current " +
                "(${String.format("%.2f", absPercentage)}% rel. change vs baseline, threshold: ${rule.threshold})"
            } else {
                "OK: $metricName $moved from $baseline to $current " +
                "(${String.format("%.2f", absPercentage)}% rel. change vs baseline, within threshold: ${rule.threshold})"
            }
        }
    }
}

data class TestRegressionAnalysis(
    val testCaseId: String,
    val currentResult: TestResult,
    val baselineResult: TestResult? = null,
    val baselineEntry: BaselineEntry? = null,
    val missingPersistedBaseline: Boolean = false,
    val regressions: List<RegressionResult>,
    val hasRegressions: Boolean = regressions.any { it.isRegression },
    val maxSeverity: RegressionSeverity = regressions.maxOfOrNull { it.severity } ?: RegressionSeverity.INFO
)

data class SuiteRegressionAnalysis(
    val currentRunId: String,
    val baselineRunId: String?,
    val testAnalyses: List<TestRegressionAnalysis>,
    val aggregateRegressions: List<RegressionResult>,
    val summary: RegressionSummary,
    val timestamp: Instant = Instant.now()
)

data class RegressionSummary(
    val totalTests: Int,
    val testsWithRegressions: Int,
    val regressionsByMetric: Map<String, Int>,
    val regressionsBySeverity: Map<RegressionSeverity, Int>,
    val overallStatus: RegressionStatus
)

enum class RegressionStatus {
    PASS,
    WARNING,
    FAILURE,
    NO_BASELINE
}

enum class BaselinePersistenceMode {
    RECORD,

    ASSERT
}

data class RegressionConfiguration(
    val rules: List<RegressionRule>,
    val enabledMetrics: Set<String> = emptySet(),
    val failOnRegression: Boolean = true,
    val baselineStrategy: BaselineStrategy = BaselineStrategy.ACTIVE,
    val baselineMode: BaselinePersistenceMode = BaselinePersistenceMode.ASSERT
) {
    companion object {
        fun defaultConfiguration(): RegressionConfiguration {
            return RegressionConfiguration(
                rules = listOf(
                    RegressionRule(
                        metricName = "correctness",
                        threshold = 0.05,
                        type = RegressionType.RELATIVE,
                        severity = RegressionSeverity.ERROR,
                        description = "Correctness regression threshold"
                    ),
                    RegressionRule(
                        metricName = "latency",
                        threshold = 0.5,
                        type = RegressionType.RELATIVE,
                        severity = RegressionSeverity.WARNING,
                        description = "Latency regression threshold"
                    ),
                    RegressionRule(
                        metricName = "token_usage",
                        threshold = 0.2,
                        type = RegressionType.RELATIVE,
                        severity = RegressionSeverity.WARNING,
                        description = "Token usage regression threshold"
                    )
                )
            )
        }
    }
}

enum class BaselineStrategy {
    ACTIVE,
    LATEST,
    TAGGED
}