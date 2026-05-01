package ru.yandex.diploma.aiplatform.application.optimization

import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.JudgeExplanation
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider

@Service
class TextGradCandidateGenerator(
    providers: List<LlmProvider>,
) {

    private val llmProvider: LlmProvider = AuxiliaryLlmProviderResolver.select(providers)

    suspend fun generate(
        originalPrompt: String,
        judgeExplanations: List<JudgeExplanation>,
        failureScoreThreshold: Double = 0.8,
    ): String? {
        val failed = judgeExplanations.filter { it.score < failureScoreThreshold }
        if (failed.isEmpty()) return null

        val prompt =
            buildString {
                failed.forEachIndexed { index, explanation ->
                    if (index > 0) appendLine()
                    append("Textual gradient ${index + 1}: ")
                    append(explanation.explanation)
                }
                appendLine()
                appendLine()
                append("Original instruction:")
                appendLine()
                append(originalPrompt)
                appendLine()
                appendLine()
                append(
                    "Based on the above feedback, write an improved version of the instruction " +
                        "that addresses the described failures. Return only the instruction text, nothing else.",
                )
                appendLine()
                appendLine("IMPORTANT: Write the instruction in Russian.")
            }

        return try {
            val response = llmProvider.generate(LlmRequest(prompt = prompt))
            OptimizedPromptResponseParser.extractPrompt(response.content)
        } catch (_: Exception) {
            null
        }
    }
}
