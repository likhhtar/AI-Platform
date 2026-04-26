package ru.yandex.diploma.aiplatform.infrastructure.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@ConfigurationProperties(prefix = "llm.providers.openai")
data class OpenAiConfig(
    var enabled: Boolean = false,
    var apiKey: String = "",
    var baseUrl: String = "https://api.openai.com",
    var defaultModel: String = "gpt-3.5-turbo",
    var timeout: Duration = Duration.ofSeconds(30),
    var maxRetries: Int = 3,
    var retryDelay: Duration = Duration.ofSeconds(1)
) {
    fun isConfigured(): Boolean {
        return enabled && apiKey.isNotBlank()
    }
}