package ru.yandex.diploma.aiplatform.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class ExecutionConfig(
    val maxParallelism: Int = 4,
    val testTimeout: Duration = 5.minutes,
    val enableParallelExecution: Boolean = true
) {
    init {
        require(maxParallelism > 0) { "Max parallelism must be positive" }
        require(testTimeout.isPositive()) { "Test timeout must be positive" }
    }
}