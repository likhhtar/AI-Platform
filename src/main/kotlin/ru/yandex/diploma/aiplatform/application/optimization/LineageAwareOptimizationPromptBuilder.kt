package ru.yandex.diploma.aiplatform.application.optimization

import kotlin.comparisons.nullsLast
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.model.PromptVersion

@Component
class LineageAwareOptimizationPromptBuilder {

    fun buildPrompt(
        currentPrompt: String,
        mutationPrompt: String,
        versions: List<PromptVersion>,
    ): String {
        if (versions.isEmpty()) {
            return buildString {
                appendLine("INSTRUCTION FOR OPTIMIZER: $mutationPrompt")
                appendLine()
                appendLine("CURRENT PROMPT TEMPLATE TO IMPROVE:")
                appendLine(currentPrompt)
                appendLine()
                appendLine("Write ONLY the improved prompt template text below. Keep all {{placeholders}} intact.")
                appendLine("IMPORTANT: Write the improved prompt in Russian only.")
            }.trimEnd()
        }

        val sorted =
            versions.sortedWith(
                compareBy(nullsLast<Double>()) { it.score },
            )

        val lineageBlock =
            buildString {
                appendLine("GENOTYPES FOUND IN ASCENDING ORDER OF QUALITY:")
                sorted.forEachIndexed { index, version ->
                    val ordinal = index + 1
                    val promptText = version.prompt ?: ""
                    val scoreValue = version.score
                    val scorePart =
                        if (scoreValue != null) {
                            "[$scoreValue]"
                        } else {
                            "[]"
                        }
                    appendLine(
                        "v$ordinal: ${quoteForDisplay(promptText)} -> score $scorePart",
                    )
                }
            }.trimEnd()

        return buildString {
            appendLine("INSTRUCTION FOR OPTIMIZER: $mutationPrompt")
            appendLine()
            appendLine("History of previous attempts:")
            appendLine(lineageBlock)
            appendLine()
            appendSectionTail(currentPrompt)
            appendLine("IMPORTANT: Write the improved prompt in Russian only.")
        }.trimEnd()
    }

    private fun StringBuilder.appendSectionTail(currentPrompt: String) {
        appendLine("Current prompt to improve:")
        appendLine(currentPrompt)
        appendLine()
        appendLine("Write ONLY the improved prompt template text below. Keep all {{placeholders}} intact.")
        appendLine("Improved prompt:")
    }

    private fun quoteForDisplay(text: String): String =
        '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
}