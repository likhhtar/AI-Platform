package ru.yandex.diploma.aiplatform.infrastructure.llm.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@ConfigurationProperties(prefix = "llm.providers.yandex-eliza")
data class YandexElizaConfig(
    var enabled: Boolean = false,
    var oauthToken: String = "",
    var baseUrl: String = "https://api.eliza.yandex.net",
    var defaultModel: String = "gpt-4.1",
    var timeout: Duration = Duration.ofSeconds(60),
    var maxRetries: Int = 3,
    var retryDelay: Duration = Duration.ofSeconds(1),
) {
    fun isConfigured(): Boolean {
        return enabled && oauthToken.isNotBlank()
    }
}
