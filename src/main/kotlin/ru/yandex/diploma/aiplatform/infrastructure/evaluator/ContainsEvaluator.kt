package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import ru.yandex.diploma.aiplatform.domain.evaluator.Evaluator
import ru.yandex.diploma.aiplatform.domain.model.EvaluationResult
import org.springframework.stereotype.Component

@Component
class ContainsEvaluator : Evaluator {
    override val evaluatorType: String = "contains"

    override suspend fun evaluate(
        output: String,
        expected: String,
        metadata: Map<String, Any>
    ): EvaluationResult {
        val caseSensitive = metadata["caseSensitive"] as? Boolean ?: true
        val trimWhitespace = metadata["trimWhitespace"] as? Boolean ?: true
        
        val processedOutput = if (trimWhitespace) output.trim() else output
        val processedExpected = if (trimWhitespace) expected.trim() else expected
        
        val contains = if (caseSensitive) {
            processedOutput.contains(processedExpected)
        } else {
            processedOutput.contains(processedExpected, ignoreCase = true)
        }
        
        return EvaluationResult(
            passed = contains,
            score = if (contains) 1.0 else 0.0,
            explanation = if (contains) {
                "Output contains expected substring: '$processedExpected'"
            } else {
                "Output does not contain expected substring: '$processedExpected'"
            }
        )
    }
}