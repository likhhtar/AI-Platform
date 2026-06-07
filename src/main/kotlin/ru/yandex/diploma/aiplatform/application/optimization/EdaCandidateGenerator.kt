package ru.yandex.diploma.aiplatform.application.optimization

import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.PromptVersion
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider

@Service
class EdaCandidateGenerator(
    providers: List<LlmProvider>,
) {
    private val llmProvider: LlmProvider = AuxiliaryLlmProviderResolver.select(providers)

    suspend fun generate(
        promptHistory: List<PromptVersion>,
        topN: Int = 5,
    ): String? {
        if (topN <= 0 || promptHistory.isEmpty()) return null

        val selected =
            promptHistory
                .filter { !it.prompt.isNullOrBlank() }
                .sortedWith(compareByDescending(nullsLast()) { it.score })
                .take(topN)
                .shuffled()
        if (selected.isEmpty()) return null

        val prompt =
            buildString {
                appendLine("Here are examples of instructions for the same task:")
                for (v in selected) {
                    append("- ")
                    appendLine(v.prompt!!.trim())
                }
                appendLine()
                appendLine(
                    "Continue this list with one new instruction that fits the same task " +
                        "and style but is different from all of the above.",
                )
                appendLine("IMPORTANT: Write the instruction in Russian.")
                appendLine("Return only the instruction text, nothing else.")
            }.trimEnd()

        val content =
            try {
                llmProvider.generate(LlmRequest(prompt = prompt)).content
            } catch (_: Exception) {
                return null
            }

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isNotEmpty()) return line
        }
        return null
    }
}
