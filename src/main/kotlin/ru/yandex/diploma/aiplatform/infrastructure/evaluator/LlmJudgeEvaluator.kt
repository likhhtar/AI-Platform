package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.config.JudgeEvaluationProperties
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.evaluator.Evaluator
import ru.yandex.diploma.aiplatform.domain.evaluator.JudgeEvaluationFailedException
import ru.yandex.diploma.aiplatform.domain.model.EvaluationResult
import ru.yandex.diploma.aiplatform.domain.model.JudgeFallbackPolicy
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest

@Component
class LlmJudgeEvaluator(
    private val providerRegistry: ProviderRegistry,
    private val judgeProperties: JudgeEvaluationProperties
) : Evaluator {

    private val logger = LoggerFactory.getLogger(LlmJudgeEvaluator::class.java)
    override val evaluatorType: String = "llm-judge"

    companion object {
        private val EVALUATION_PROMPT_TEMPLATE = """
            Ты судья, оценивающий качество ответа языковой модели.
            
            Ниже приведена инструкция по оценке со шкалой. Применяй её буквально —
            не придумывай своих критериев, используй только те, что описаны в шкале.
            
            ИНСТРУКЦИЯ ПО ОЦЕНКЕ (это не эталонный ответ, это правила оценки):
            {{expected}}
            
            ОТВЕТ АГЕНТА, КОТОРЫЙ НУЖНО ОЦЕНИТЬ:
            {{actual}}
            
            Выведи результат строго в формате ниже, без отклонений:
            Score: [число от 0.0 до 1.0, например 0.3 или 0.7]
            Explanation: [одно-два предложения: какой критерий шкалы сработал и почему]
        """.trimIndent()

        private val SYSTEM_PROMPT = "Ты точный судья. Первым делом выведи 'Score: X.X' — только число, без скобок и лишних символов. Затем 'Explanation:' с кратким обоснованием. Никакого другого текста до Score."
    }

    override suspend fun evaluate(
        output: String,
        expected: String,
        metadata: Map<String, Any>
    ): EvaluationResult {
        return try {
            val judgeConfig = extractJudgeConfig(metadata)
            val evaluationPrompt = buildEvaluationPrompt(output, expected)

            logger.debug("Using LLM judge: provider=${judgeConfig.provider}, model=${judgeConfig.model}")

            val provider = providerRegistry.getProvider(judgeConfig.provider)
            val llmRequest = LlmRequest(
                prompt = evaluationPrompt,
                systemPrompt = SYSTEM_PROMPT,
                model = judgeConfig.model,
                temperature = 0.1,
                maxTokens = 500
            )

            val response = provider.generate(llmRequest)
            logger.debug("LLM judge raw response: ${response.content}")
            parseEvaluationResponse(response.content)
        } catch (e: Exception) {
            logger.error("LLM judge evaluation failed", e)
            when (judgeProperties.fallbackPolicy) {
                JudgeFallbackPolicy.FAIL_FAST -> throw JudgeEvaluationFailedException(
                    "LLM judge evaluation failed and fallbackPolicy=FAIL_FAST: ${e.message}",
                    e
                )
                JudgeFallbackPolicy.HEURISTIC -> {
                    val fallbackPassed = output.lowercase().contains(expected.lowercase())
                    EvaluationResult(
                        passed = fallbackPassed,
                        score = if (fallbackPassed) 0.7 else 0.0,
                        explanation = "LLM judge evaluation failed (${e.message}); HEURISTIC fallback used; passed=$fallbackPassed"
                    )
                }
            }
        }
    }

    private fun extractJudgeConfig(metadata: Map<String, Any>): JudgeConfig {
        val judgeAgentName = metadata["judgeAgentName"] as? String ?: judgeProperties.defaultAgentName
        val judgeProvider = metadata["judgeProvider"] as? String ?: judgeProperties.defaultProvider
        val judgeModel = metadata["judgeModel"] as? String ?: judgeProperties.defaultModel

        return JudgeConfig(
            agentName = judgeAgentName,
            provider = judgeProvider,
            model = judgeModel
        )
    }

    private fun buildEvaluationPrompt(actual: String, expected: String): String {
        return EVALUATION_PROMPT_TEMPLATE
            .replace("{{expected}}", expected)
            .replace("{{actual}}", actual)
    }

    private fun parseEvaluationResponse(response: String): EvaluationResult {
        val lines = response.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val scoreLine = lines.find { it.startsWith("Score:", ignoreCase = true) }
        var score = scoreLine?.substringAfter(":")?.trim()
            ?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)

        if (score == null) {
            score = lines.firstOrNull()
                ?.replace(Regex("[^0-9.]"), "")
                ?.toDoubleOrNull()
                ?.coerceIn(0.0, 1.0)
        }

        if (score == null) {
            val numberRegex = Regex("""0\.\d+|1\.0""")
            score = numberRegex.find(response)?.value?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
        }

        if (score == null) {
            logger.warn("Could not parse score from judge response, defaulting to 0.5. Response was: ${response.take(200)}")
            score = 0.5
        }

        val explanationLine = lines.find { it.startsWith("Explanation:", ignoreCase = true) }
        val explanation = explanationLine?.substringAfter(":")?.trim()
            ?: lines.drop(1).joinToString(" ").ifBlank { "No explanation provided" }

        val passed = score >= 0.75

        logger.debug("LLM judge result: score=$score, passed=$passed, explanation=$explanation")

        return EvaluationResult(
            passed = passed,
            score = score,
            explanation = explanation
        )
    }

    private data class JudgeConfig(
        val agentName: String,
        val provider: String,
        val model: String
    )
}