package ru.yandex.diploma.aiplatform.domain.model

enum class MetricDirection {
    HIGHER_IS_BETTER,

    LOWER_IS_BETTER
}

object MetricDirections {
    fun forMetric(metricName: String): MetricDirection = when (metricName) {
        "correctness", "average_score", "success_rate" -> MetricDirection.HIGHER_IS_BETTER
        "latency", "token_usage" -> MetricDirection.HIGHER_IS_BETTER
        "average_latency", "total_tokens", "response_time", "cost" -> MetricDirection.LOWER_IS_BETTER
        else -> MetricDirection.HIGHER_IS_BETTER
    }
}
