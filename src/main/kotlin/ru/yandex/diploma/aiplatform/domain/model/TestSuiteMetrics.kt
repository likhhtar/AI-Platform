package ru.yandex.diploma.aiplatform.domain.model

data class TestSuiteMetrics(
    val averageLatency: Double,
    val totalTokens: Int,
    val averageScore: Double
) {
    companion object {
        fun calculate(results: List<TestResult>): TestSuiteMetrics {
            if (results.isEmpty()) {
                return TestSuiteMetrics(
                    averageLatency = 0.0,
                    totalTokens = 0,
                    averageScore = 0.0
                )
            }

            val averageLatency = results.map { it.executionTimeMs }.average()
            val totalTokens = results.mapNotNull { it.llmResponse?.tokensUsed }.sum()
            val averageScore = results.map { it.evaluationResult.score }.average()

            return TestSuiteMetrics(
                averageLatency = averageLatency,
                totalTokens = totalTokens,
                averageScore = averageScore
            )
        }
    }
}