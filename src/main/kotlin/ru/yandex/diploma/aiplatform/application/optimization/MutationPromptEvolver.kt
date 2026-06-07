package ru.yandex.diploma.aiplatform.application.optimization

import java.util.Locale
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider

@Service
class MutationPromptEvolver(
    providers: List<LlmProvider>,
) {

    private val llmProvider: LlmProvider = AuxiliaryLlmProviderResolver.select(providers)

    suspend fun evolve(
        currentMutationPrompt: String,
        successRate: Double,
    ): String {
        val pct = String.format(Locale.US, "%.1f", successRate * 100)
        val prompt =
            buildString {
                appendLine("Please summarize and improve the following instruction")
                appendLine("that is used to mutate/improve other prompts.")
                appendLine("Current success rate of this instruction: ${pct}%")
                appendLine()
                appendLine("Instruction to improve:")
                appendLine(currentMutationPrompt)
                appendLine()
                appendLine("IMPORTANT: Write the instruction in Russian.")
                appendLine("Write an improved version. Return only the instruction text, nothing else.")
            }.trimEnd()

        return try {
            val response = llmProvider.generate(LlmRequest(prompt = prompt))
            OptimizedPromptResponseParser.extractPrompt(response.content) ?: currentMutationPrompt
        } catch (_: Exception) {
            currentMutationPrompt
        }
    }
}
