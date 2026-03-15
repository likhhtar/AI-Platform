package ru.yandex.diploma.aiplatform.config

import org.springframework.boot.context.properties.ConfigurationProperties
import ru.yandex.diploma.aiplatform.domain.model.JudgeFallbackPolicy

@ConfigurationProperties(prefix = "aiplatform.judge")
data class JudgeEvaluationProperties(
    var fallbackPolicy: JudgeFallbackPolicy = JudgeFallbackPolicy.FAIL_FAST,
    var defaultProvider: String = "openai",
    var defaultModel: String = "gpt-4",
    var defaultAgentName: String = "judge-agent"
)
