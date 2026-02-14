package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import ru.yandex.diploma.aiplatform.domain.evaluator.Evaluator
import ru.yandex.diploma.aiplatform.domain.model.EvaluationResult
import org.springframework.stereotype.Component

@Component
class ExactMatchEvaluator : Evaluator {
    override val evaluatorType: String = "exact"

    override fun evaluate(
        output: String,
        expected: String,
        metadata: Map<String, Any>
    ): EvaluationResult {
        val caseSensitive = metadata["caseSensitive"] as? Boolean ?: true
        val trimWhitespace = metadata["trimWhitespace"] as? Boolean ?: true
        
        val processedOutput = if (trimWhitespace) output.trim() else output
        val processedExpected = if (trimWhitespace) expected.trim() else expected
        
        val matches = if (caseSensitive) {
            processedOutput == processedExpected
        } else {
            processedOutput.equals(processedExpected, ignoreCase = true)
        }
        
        return EvaluationResult(
            passed = matches,
            score = if (matches) 1.0 else 0.0,
            explanation = if (matches) {
                "Output exactly matches expected result"
            } else {
                "Output does not match expected result. Expected: '$processedExpected', Got: '$processedOutput'"
            }
        )
    }
}