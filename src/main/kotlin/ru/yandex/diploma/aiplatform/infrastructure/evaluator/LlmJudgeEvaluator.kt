package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import kotlinx.coroutines.runBlocking
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
            You are an expert evaluator. Your task is to evaluate the correctness and quality of an AI assistant's response.
            
            **Expected Answer:**
            {{expected}}
            
            **Actual Answer:**
            {{actual}}
            
            **Evaluation Criteria:**
            - Correctness: Does the actual answer match the expected answer in meaning and content?
            - Completeness: Does the actual answer address all aspects of the expected answer?
            - Quality: Is the actual answer well-structured and clear?
            
            **Instructions:**
            1. Provide a score from 0.0 to 1.0 where:
               - 0.0 = Completely incorrect or irrelevant
               - 0.5 = Partially correct but missing important elements
               - 1.0 = Fully correct and complete
            
            2. Provide a brief explanation of your evaluation.
            
            **Response Format:**
            Score: [0.0-1.0]
            Explanation: [Your detailed explanation]
        """.trimIndent()
    }
    
    override fun evaluate(
        output: String,
        expected: String,
        metadata: Map<String, Any>
    ): EvaluationResult {
        return try {
            runBlocking {
                val judgeConfig = extractJudgeConfig(metadata)
                val evaluationPrompt = buildEvaluationPrompt(output, expected)
                
                logger.debug("Using LLM judge: provider=${judgeConfig.provider}, model=${judgeConfig.model}")
                
                val provider = providerRegistry.getProvider(judgeConfig.provider)
                val llmRequest = LlmRequest(
                    prompt = evaluationPrompt,
                    systemPrompt = "You are a precise and fair evaluator. Always provide scores in the exact format requested.",
                    model = judgeConfig.model,
                    temperature = 0.1,
                    maxTokens = 500
                )
                
                val response = provider.generate(llmRequest)
                parseEvaluationResponse(response.content)
            }
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
        val lines = response.lines().map { it.trim() }
        
        val scoreLine = lines.find { it.startsWith("Score:", ignoreCase = true) }
        val score = scoreLine?.let { line ->
            val scoreText = line.substringAfter(":", "").trim()
            scoreText.toDoubleOrNull()?.coerceIn(0.0, 1.0)
        } ?: run {
            val numberRegex = """(\d+\.?\d*)""".toRegex()
            val numbers = numberRegex.findAll(response)
                .map { it.value.toDoubleOrNull() }
                .filterNotNull()
                .filter { it >= 0.0 && it <= 1.0 }
            
            numbers.firstOrNull() ?: 0.5
        }
        
        val explanationLine = lines.find { it.startsWith("Explanation:", ignoreCase = true) }
        val explanation = explanationLine?.substringAfter(":", "")?.trim()
            ?: lines.drop(1).joinToString(" ").ifBlank { "No explanation provided" }
        
        val passed = score >= 0.5
        
        logger.debug("LLM judge evaluation: score=$score, passed=$passed")
        
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