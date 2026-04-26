package ru.yandex.diploma.aiplatform.domain.model

data class ComparisonResult(
    val testCase: TestCase,
    val agentResults: Map<String, SingleAgentResult>,
    val executionTimeMs: Long
) {
    val success: Boolean = agentResults.values.any { it.success }
    val bestPerformingAgent: String? = agentResults.entries
        .filter { it.value.success }
        .maxByOrNull { it.value.evaluationResult.score }
        ?.key
}

data class SingleAgentResult(
    val agentName: String,
    val success: Boolean,
    val evaluationResult: EvaluationResult,
    val llmResponse: LlmResponse?,
    val executionTimeMs: Long,
    val error: String?
)