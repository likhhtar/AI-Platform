package ru.yandex.diploma.aiplatform.application.usecase

import ru.yandex.diploma.aiplatform.domain.model.BaselineEntry
import ru.yandex.diploma.aiplatform.domain.model.PromptVersion
import ru.yandex.diploma.aiplatform.domain.model.TestResult
import java.time.Instant

fun TestResult.toBaselineEntry(promptVersion: PromptVersion? = null): BaselineEntry {
    val m = LinkedHashMap<String, Double>()
    m["correctness"] = evaluationResult.score
    metrics.forEach { (k, v) -> m[k] = v.score }
    return BaselineEntry(
        response = llmResponse?.content ?: "",
        metrics = m,
        createdAt = Instant.now(),
        promptVersion = promptVersion,
    )
}
