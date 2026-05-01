package ru.yandex.diploma.aiplatform.infrastructure.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.infrastructure.llm.config.OpenAiConfig
import ru.yandex.diploma.aiplatform.infrastructure.llm.config.OpenRouterConfig
import ru.yandex.diploma.aiplatform.infrastructure.llm.config.YandexElizaConfig

@Component
class LlmInfrastructureStartupValidator(
    private val providers: List<LlmProvider>,
    private val openAiConfig: OpenAiConfig,
    private val openRouterConfig: OpenRouterConfig,
    private val yandexElizaConfig: YandexElizaConfig,
) {

    @PostConstruct
    fun validate() {
        if (providers.isEmpty()) {
            throw IllegalStateException(
                "No LlmProvider beans are registered. For local/tests enable llm.providers.deterministic=true " +
                    "(test profile). For production enable openai or openrouter with a non-blank API key."
            )
        }
        if (openAiConfig.enabled && openAiConfig.apiKey.isBlank()) {
            throw IllegalStateException(
                "llm.providers.openai.enabled=true but llm.providers.openai.api-key is blank. " +
                    "Set OPENAI_API_KEY or disable the provider."
            )
        }
        if (openRouterConfig.enabled && openRouterConfig.apiKey.isBlank()) {
            throw IllegalStateException(
                "llm.providers.openrouter.enabled=true but llm.providers.openrouter.api-key is blank. " +
                    "Set OPENROUTER_API_KEY or disable the provider."
            )
        }
        if (openAiConfig.enabled && providers.none { it.providerId == "openai" }) {
            throw IllegalStateException(
                "OpenAI is enabled in configuration but OpenAiLlmProvider is not registered (check conditional beans)."
            )
        }
        if (openRouterConfig.enabled && providers.none { it.providerId == "openrouter" }) {
            throw IllegalStateException(
                "OpenRouter is enabled in configuration but OpenRouterLlmProvider is not registered (check conditional beans)."
            )
        }
        if (yandexElizaConfig.enabled && yandexElizaConfig.oauthToken.isBlank()) {
            throw IllegalStateException(
                "llm.providers.yandex-eliza.enabled=true but llm.providers.yandex-eliza.oauth-token is blank. " +
                    "Set SOY_TOKEN or disable the provider."
            )
        }
        if (yandexElizaConfig.enabled && providers.none { it.providerId == "yandex-eliza" }) {
            throw IllegalStateException(
                "Yandex Eliza is enabled in configuration but YandexElizaLlmProvider is not registered (check conditional beans)."
            )
        }
    }
}
