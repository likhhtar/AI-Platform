package ru.yandex.diploma.aiplatform.domain.model

enum class MetricType {
    CORRECTNESS,
    LATENCY,
    TOKEN_USAGE,
    CUSTOM
}

data class MetricDefinition(
    val name: String,
    val type: MetricType,
    val evaluatorType: String,
    val weight: Double = 1.0,
    val threshold: Double? = null,
    val config: Map<String, Any> = emptyMap(),
    val description: String = ""
) {
    init {
        require(name.isNotBlank()) { "Metric name cannot be blank" }
        require(weight > 0.0) { "Metric weight must be positive" }
        threshold?.let { require(it >= 0.0 && it <= 1.0) { "Threshold must be between 0.0 and 1.0" } }
    }
}

data class EvaluationContext(
    val testCase: TestCase,
    val llmResponse: LlmResponse?,
    val executionTimeMs: Long,
    val metadata: Map<String, Any> = emptyMap()
)

data class TestCaseWithMetrics(
    val promptId: String,
    val agentName: String? = null,
    val agentNames: List<String> = emptyList(),
    val variables: Map<String, String>,
    val expected: String,
    val metrics: List<MetricDefinition>,
    val metadata: Map<String, Any> = emptyMap()
) {
    init {
        require(promptId.isNotBlank()) { "Prompt ID cannot be blank" }
        require(metrics.isNotEmpty()) { "At least one metric must be defined" }
        
        val hasAgentName = !agentName.isNullOrBlank()
        val hasAgentNames = agentNames.isNotEmpty()
        require(hasAgentName || hasAgentNames) { "Either agentName or agentNames must be specified" }
        require(!(hasAgentName && hasAgentNames)) { "Cannot specify both agentName and agentNames" }
    }
    
    fun getAllAgentNames(): List<String> {
        return when {
            !agentName.isNullOrBlank() -> listOf(agentName)
            agentNames.isNotEmpty() -> agentNames
            else -> emptyList()
        }
    }
    
    fun isMultiAgentTest(): Boolean = agentNames.isNotEmpty()
    
    val evaluatorType: String
        get() = metrics.find { it.type == MetricType.CORRECTNESS }?.evaluatorType 
            ?: throw IllegalStateException("No correctness metric defined")
}

fun TestCase.toTestCaseWithMetrics(): TestCaseWithMetrics {
    return TestCaseWithMetrics(
        promptId = this.promptId,
        agentName = this.agentName,
        agentNames = this.agentNames,
        variables = this.variables,
        expected = this.expected,
        metrics = listOf(
            MetricDefinition(
                name = "correctness",
                type = MetricType.CORRECTNESS,
                evaluatorType = this.evaluatorType,
                weight = 1.0,
                threshold = 0.5
            )
        ),
        metadata = this.metadata
    )
}

fun TestCaseWithMetrics.toTestCase(): TestCase {
    return TestCase(
        promptId = this.promptId,
        agentName = this.agentName,
        agentNames = this.agentNames,
        variables = this.variables,
        expected = this.expected,
        evaluatorType = this.evaluatorType,
        metadata = this.metadata
    )
}