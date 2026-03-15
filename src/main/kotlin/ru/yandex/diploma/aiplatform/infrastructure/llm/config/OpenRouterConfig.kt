package ru.yandex.diploma.aiplatform.infrastructure.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@ConfigurationProperties(prefix = "llm.providers.openrouter")
data class OpenRouterConfig(
    var enabled: Boolean = false,
    var apiKey: String = "",
    var baseUrl: String = "https://openrouter.ai/api/v1",
    var defaultModel: String = "deepseek/deepseek-chat",
    var timeout: Duration = Duration.ofSeconds(30),
    var maxRetries: Int = 3,
    var retryDelay: Duration = Duration.ofSeconds(1)
) {
    fun isConfigured(): Boolean = enabled && apiKey.isNotBlank()
}
