package ru.yandex.diploma.aiplatform.domain.model

import java.time.Instant

data class BaselineEntry(
    val response: String,
    val metrics: Map<String, Double>,
    val createdAt: Instant,
    val promptVersion: PromptVersion? = null,
)
