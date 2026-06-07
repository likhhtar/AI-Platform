package ru.yandex.diploma.aiplatform.`interface`.rest

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.NotBlank
import ru.yandex.diploma.aiplatform.domain.model.BaselinePersistenceMode
import ru.yandex.diploma.aiplatform.domain.model.OptimizationRunSummary

data class RunTestsRequest(
    @field:NotBlank(message = "Configuration cannot be blank")
    val configuration: String,
    val baselineMode: BaselinePersistenceMode? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RunTestsResponse(
    val success: Boolean,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val successRate: Double,
    val executionTimeMs: Long,
    val results: List<TestResultDto>,
    val reportPath: String? = null,
    val optimizationReportPath: String? = null,
    val optimization: OptimizationRunSummary? = null,
    val runId: String? = null,
    val regressionStatus: String? = null,
    val regressionCount: Int? = null,
    val error: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TestResultDto(
    val promptId: String,
    val agentName: String,
    val variables: Map<String, String>,
    val expected: String,
    val actual: String?,
    val passed: Boolean,
    val score: Double,
    val explanation: String,
    val executionTimeMs: Long,
    val metrics: Map<String, Double>? = null,
    val error: String? = null
)

data class RunExperimentRequest(
    @field:NotBlank(message = "Experiment name cannot be blank")
    val name: String,
    val description: String = "",
    val models: List<String>,
    val temperatures: List<Double>,
    val maxParallelism: Int = 4,
    val enableParallelExecution: Boolean = true,
    val tags: List<String> = emptyList()
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RunExperimentResponse(
    val success: Boolean,
    val experimentId: String,
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val executionTimeMs: Long,
    val bestConfiguration: BestConfigurationDto?,
    val error: String? = null
)

data class BestConfigurationDto(
    val model: String,
    val temperature: Double,
    val description: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EnhancedRunTestsResponse(
    val success: Boolean,
    val suiteId: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val successRate: Double,
    val executionTimeMs: Long,
    val metrics: MetricsDto?,
    val results: List<EnhancedTestResultDto>,
    val error: String? = null
)

data class MetricsDto(
    val averageLatency: Double,
    val totalTokens: Int,
    val averageScore: Double,
    val estimatedCost: java.math.BigDecimal,
    val modelBreakdown: Map<String, ModelMetricsDto>
)

data class ModelMetricsDto(
    val model: String,
    val testCount: Int,
    val averageLatency: Double,
    val totalTokens: Int,
    val averageScore: Double,
    val successRate: Double,
    val estimatedCost: java.math.BigDecimal
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EnhancedTestResultDto(
    val promptId: String,
    val agentNames: List<String>,
    val variables: Map<String, String>,
    val expected: String,
    val actual: String?,
    val passed: Boolean,
    val score: Double,
    val explanation: String,
    val executionTimeMs: Long,
    val tokensUsed: Int?,
    val model: String?,
    val error: String? = null,
    val comparisonResult: ComparisonResultDto? = null
)

data class ComparisonResultDto(
    val bestPerformingAgent: String?,
    val agentResults: Map<String, AgentResultDto>,
    val metrics: ComparisonMetricsDto
)

data class AgentResultDto(
    val agentName: String,
    val score: Double,
    val passed: Boolean,
    val executionTimeMs: Long,
    val tokensUsed: Int?,
    val model: String?
)

data class ComparisonMetricsDto(
    val scoreVariance: Double,
    val latencyVariance: Double,
    val consensusScore: Double,
    val tokenEfficiency: Map<String, Double>
)