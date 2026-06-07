package ru.yandex.diploma.aiplatform.application.optimization

import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.JudgeExplanation
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider

/**
 * Lamarckian mutation in the sense of PromptBreeder: the auxiliary model recovers the
 * instruction from successful working-outs (evaluator explanations where the score meets the
 * threshold), analogous to Lamarckian inheritance of acquired traits—behaviour that already
 * succeeded is folded back into the genotype (the instruction text).
 */
@Service
class LamarckianCandidateGenerator(
    providers: List<LlmProvider>,
) {

    private val llmProvider: LlmProvider = AuxiliaryLlmProviderResolver.select(providers)

    suspend fun generate(
        judgeExplanations: List<JudgeExplanation>,
        successScoreThreshold: Double = 0.8,
    ): String? {
        if (judgeExplanations.isEmpty()) return null
        val successful = judgeExplanations.filter { it.score >= successScoreThreshold }
        if (successful.isEmpty()) return null

        val prompt =
            buildString {
                append(
                    "I gave a friend an instruction and some advice. " +
                        "Here are the correct examples of his workings out: ",
                )
                successful.forEachIndexed { index, explanation ->
                    if (index > 0) appendLine()
                    append(explanation.explanation)
                }
                append(" The instruction was:")
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
