package ru.yandex.diploma.aiplatform.domain.model

import java.time.Instant
import java.util.UUID

data class TestRunRecord(
    val runId: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val suiteMetadata: TestSuiteMetadata,
    val promptVersion: String,
    val configurationHash: String,
    val results: List<TestResult>,
    val metrics: TestSuiteMetrics,
    val executionTimeMs: Long,
    val environment: ExecutionEnvironment = ExecutionEnvironment(),
    val tags: Set<String> = emptySet()
) {
    val successRate: Double = if (results.isNotEmpty()) {
        results.count { it.success }.toDouble() / results.size
    } else 0.0

    val hasInfrastructureErrors: Boolean
        get() = results.any { it.infrastructureError }
}

data class ExecutionEnvironment(
    val hostname: String = System.getProperty("os.name", "unknown"),
    val javaVersion: String = System.getProperty("java.version", "unknown"),
    val timestamp: Instant = Instant.now(),
    val gitCommit: String? = null,
    val branch: String? = null,
    val buildNumber: String? = null
)

data class BaselineConfiguration(
    val baselineRunId: String,
    val description: String = "",
    val createdAt: Instant = Instant.now(),
    val createdBy: String = "system",
    val isActive: Boolean = true
)

data class OptimizationRunSummary(
    val iterationRounds: Int,
    val mode: String,
    val optimizerType: String,
    val executionTimeMs: Long,
    val optimizationStatus: String,
)

data class EnhancedTestSuiteResult(
    val runId: String,
    val testRun: TestRunRecord,
    val regressionAnalysis: SuiteRegressionAnalysis,
    val reportFile: java.io.File?,
    val optimizationReportFile: java.io.File? = null,
    val optimizationSummary: OptimizationRunSummary? = null,
    val exitCode: Int
)