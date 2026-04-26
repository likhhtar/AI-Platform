package ru.yandex.diploma.aiplatform.domain.model

data class EvaluationResult(
    val passed: Boolean,
    val score: Double,
    val explanation: String,
    val metadata: Map<String, Any> = emptyMap()
) {
    init {
        require(score >= 0.0 && score <= 1.0) { "Score must be between 0.0 and 1.0" }
    }
}