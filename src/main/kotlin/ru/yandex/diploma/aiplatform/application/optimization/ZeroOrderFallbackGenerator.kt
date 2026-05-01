package ru.yandex.diploma.aiplatform.application.optimization

import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider

@Service
class ZeroOrderFallbackGenerator(
    providers: List<LlmProvider>,
) {

    private val llmProvider: LlmProvider = AuxiliaryLlmProviderResolver.select(providers)

    suspend fun generate(problemDescription: String): String? {
        val prompt =
            buildString {
                appendLine("A list of 10 different ways to instruct an LLM to do the following task:")
                appendLine(problemDescription)
                appendLine("IMPORTANT: Write the instruction in Russian.")
                appendLine()
                appendLine("1.")
            }.trimEnd()

        val content =
            try {
                llmProvider.generate(LlmRequest(prompt = prompt)).content
            } catch (_: Exception) {
                return null
            }

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimStart()
            if (!line.startsWith("1.")) continue
            val rest = line.removePrefix("1.").trimStart()
            if (rest.isNotEmpty()) return rest
        }
        return null
    }
}
