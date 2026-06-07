package ru.yandex.diploma.aiplatform.application.optimization

import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider

internal object AuxiliaryLlmProviderResolver {

    fun select(providers: List<LlmProvider>): LlmProvider {
        require(providers.isNotEmpty()) {
            "No LlmProvider beans registered. Enable llm.providers.deterministic.enabled or a real provider."
        }
        return providers.firstOrNull { it.providerId != "deterministic" }
            ?: providers.first()
    }
}
