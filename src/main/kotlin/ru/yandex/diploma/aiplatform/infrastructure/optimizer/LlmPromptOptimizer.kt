package ru.yandex.diploma.aiplatform.infrastructure.optimizer

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizer
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException
import java.time.Instant

@Component
class LlmPromptOptimizer(
    private val providerRegistry: ProviderRegistry
) : PromptOptimizer {
    
    private val logger = LoggerFactory.getLogger(LlmPromptOptimizer::class.java)
    
    override val optimizerType: OptimizerType = OptimizerType.LLM
    
    override suspend fun optimize(input: OptimizationInput, config: OptimizationConfig): OptimizationResult {
        val startTime = System.currentTimeMillis()
        
        try {
            val llmConfig = config.llmConfig 
                ?: throw PromptOptimizationException("LLM config is required for LLM optimizer", optimizerType = optimizerType)
            
            val provider = providerRegistry.getProvider(llmConfig.provider)
            
            logger.info("Starting LLM-based prompt optimization for prompt: ${input.originalPrompt.id}")
            
            val metaPrompt = buildMetaPrompt(input, config.mode)
            
            val llmRequest = LlmRequest(
                prompt = metaPrompt,
                model = llmConfig.model,
                temperature = llmConfig.temperature,
                maxTokens = llmConfig.maxTokens ?: 2000,
                topP = null,
                frequencyPenalty = null,
                presencePenalty = null,
                additionalParameters = emptyMap()
            )
            
            val response = provider.generate(llmRequest)
            val optimizationResponse = parseOptimizationResponse(response.content, config.mode)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            logger.info("LLM optimization completed in ${executionTime}ms with confidence: ${optimizationResponse.confidence}")
            
            return OptimizationResult(
                originalPrompt = input.originalPrompt,
                optimizedPrompt = optimizationResponse.optimizedPrompt,
                suggestions = optimizationResponse.suggestions,
                confidence = optimizationResponse.confidence,
                reasoning = optimizationResponse.reasoning,
                metadata = mapOf(
                    "provider" to llmConfig.provider,
                    "model" to llmConfig.model,
                    "temperature" to llmConfig.temperature,
                    "mode" to config.mode.name,
                    "testCasesCount" to input.testCases.size,
                    "averageScore" to input.testResults.map { it.evaluationResult.score }.average()
                ),
                executionTimeMs = executionTime
            )
            
        } catch (e: PromptOptimizationException) {
            throw e
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            logger.error("LLM optimization failed", e)
            throw PromptOptimizationException(
                "LLM optimization failed: ${e.message}",
                cause = e,
                optimizerType = optimizerType
            )
        }
    }
    
    override suspend fun isAvailable(): Boolean {
        return try {
            providerRegistry.getAvailableProviders().isNotEmpty()
        } catch (e: Exception) {
            logger.warn("Failed to check LLM optimizer availability", e)
            false
        }
    }
    
    override fun getConfigurationRequirements(): List<String> {
        return listOf(
            "llmConfig.provider - LLM provider name",
            "llmConfig.model - Model name to use for optimization",
            "llmConfig.temperature - Temperature for generation (optional, default: 0.3)",
            "llmConfig.maxTokens - Maximum tokens for response (optional, default: 2000)",
            "llmConfig.systemPrompt - Custom system prompt (optional)"
        )
    }
    
    private fun buildMetaPrompt(input: OptimizationInput, mode: OptimizationMode): String {
        val failedTests = input.testResults.filter { !it.evaluationResult.passed }
        val lowScoreTests = input.testResults.filter { it.evaluationResult.score < 0.7 }
        
        return buildString {
            appendLine("# Prompt Optimization Task")
            appendLine()
            appendLine("You are an expert prompt engineer. Your task is to analyze the given prompt and test results, then provide optimization recommendations.")
            appendLine()
            
            appendLine("## Original Prompt")
            appendLine("**ID:** ${input.originalPrompt.id}")
            appendLine("**Name:** ${input.originalPrompt.name}")
            appendLine("**Template:**")
            appendLine("```")
            appendLine(input.originalPrompt.template)
            appendLine("```")
            appendLine()
            
            appendLine("## Agent Configuration")
            appendLine("**Name:** ${input.agentConfig.name}")
            appendLine("**Model:** ${input.agentConfig.model ?: "default"}")
            appendLine("**Temperature:** ${input.agentConfig.temperature}")
            appendLine("**System Prompt:** ${input.agentConfig.systemPrompt}")
            appendLine()
            
            appendLine("## Test Cases and Results")
            input.testResults.forEachIndexed { index, result ->
                appendLine("### Test Case ${index + 1}")
                appendLine("**Variables:** ${result.testCase.variables}")
                appendLine("**Expected:** ${result.testCase.expected}")
                appendLine("**Actual Response:** ${result.llmResponse?.content ?: "N/A"}")
                appendLine("**Score:** ${result.evaluationResult.score}")
                appendLine("**Passed:** ${result.evaluationResult.passed}")
                appendLine("**Evaluation:** ${result.evaluationResult.explanation}")
                appendLine()
            }
            
            if (failedTests.isNotEmpty()) {
                appendLine("## Failed Tests Analysis")
                appendLine("${failedTests.size} out of ${input.testResults.size} tests failed.")
                failedTests.forEach { result ->
                    appendLine("- **Issue:** ${result.evaluationResult.explanation}")
                }
                appendLine()
            }
            
            if (lowScoreTests.isNotEmpty()) {
                appendLine("## Low Score Tests Analysis")
                appendLine("${lowScoreTests.size} tests scored below 0.7:")
                lowScoreTests.forEach { result ->
                    appendLine("- **Score ${result.evaluationResult.score}:** ${result.evaluationResult.explanation}")
                }
                appendLine()
            }
            
            appendLine("## Task")
            when (mode) {
                OptimizationMode.SUGGEST -> {
                    appendLine("Provide optimization suggestions for the prompt. Focus on:")
                    appendLine("1. Improving clarity and specificity")
                    appendLine("2. Addressing failed test cases")
                    appendLine("3. Enhancing overall performance")
                    appendLine()
                    appendLine("Respond in the following JSON format:")
                    appendLine("```json")
                    appendLine("{")
                    appendLine("  \"suggestions\": [")
                    appendLine("    {")
                    appendLine("      \"type\": \"CLARITY|SPECIFICITY|LENGTH|STRUCTURE|CONTEXT|EXAMPLES|CONSTRAINTS|FORMAT|TONE|OTHER\",")
                    appendLine("      \"description\": \"Description of the suggestion\",")
                    appendLine("      \"originalText\": \"Text to be changed (if applicable)\",")
                    appendLine("      \"suggestedText\": \"Suggested replacement text (if applicable)\",")
                    appendLine("      \"impact\": \"LOW|MEDIUM|HIGH|CRITICAL\",")
                    appendLine("      \"confidence\": 0.8,")
                    appendLine("      \"reasoning\": \"Why this suggestion would help\"")
                    appendLine("    }")
                    appendLine("  ],")
                    appendLine("  \"confidence\": 0.8,")
                    appendLine("  \"reasoning\": \"Overall analysis and reasoning\"")
                    appendLine("}")
                    appendLine("```")
                }
                OptimizationMode.APPLY -> {
                    appendLine("Create an improved version of the prompt and provide suggestions. Focus on:")
                    appendLine("1. Fixing issues identified in failed tests")
                    appendLine("2. Improving clarity and specificity")
                    appendLine("3. Maintaining the original intent")
                    appendLine()
                    appendLine("Respond in the following JSON format:")
                    appendLine("```json")
                    appendLine("{")
                    appendLine("  \"optimizedPrompt\": {")
                    appendLine("    \"id\": \"${input.originalPrompt.id}_optimized\",")
                    appendLine("    \"name\": \"${input.originalPrompt.name} (Optimized)\",")
                    appendLine("    \"template\": \"Your improved prompt template here\"")
                    appendLine("  },")
                    appendLine("  \"suggestions\": [")
                    appendLine("    {")
                    appendLine("      \"type\": \"CLARITY|SPECIFICITY|LENGTH|STRUCTURE|CONTEXT|EXAMPLES|CONSTRAINTS|FORMAT|TONE|OTHER\",")
                    appendLine("      \"description\": \"Description of the change made\",")
                    appendLine("      \"originalText\": \"Original text\",")
                    appendLine("      \"suggestedText\": \"New text\",")
                    appendLine("      \"impact\": \"LOW|MEDIUM|HIGH|CRITICAL\",")
                    appendLine("      \"confidence\": 0.8,")
                    appendLine("      \"reasoning\": \"Why this change was made\"")
                    appendLine("    }")
                    appendLine("  ],")
                    appendLine("  \"confidence\": 0.8,")
                    appendLine("  \"reasoning\": \"Overall analysis and reasoning for the optimization\"")
                    appendLine("}")
                    appendLine("```")
                }
            }
        }
    }
    
    private fun parseOptimizationResponse(content: String, mode: OptimizationMode): ParsedOptimizationResponse {
        return try {
            val jsonStart = content.indexOf("{")
            val jsonEnd = content.lastIndexOf("}") + 1
            
            if (jsonStart == -1 || jsonEnd <= jsonStart) {
                throw IllegalArgumentException("No valid JSON found in response")
            }
            
            val jsonContent = content.substring(jsonStart, jsonEnd)
            
            val suggestions = extractSuggestions(jsonContent)
            val confidence = extractConfidence(jsonContent)
            val reasoning = extractReasoning(jsonContent)
            val optimizedPrompt = if (mode == OptimizationMode.APPLY) {
                extractOptimizedPrompt(jsonContent)
            } else null
            
            ParsedOptimizationResponse(
                optimizedPrompt = optimizedPrompt,
                suggestions = suggestions,
                confidence = confidence,
                reasoning = reasoning
            )
            
        } catch (e: Exception) {
            logger.warn("Failed to parse optimization response, using fallback", e)
            
            ParsedOptimizationResponse(
                optimizedPrompt = null,
                suggestions = listOf(
                    OptimizationSuggestion(
                        type = SuggestionType.OTHER,
                        description = "LLM provided general optimization advice",
                        originalText = null,
                        suggestedText = null,
                        impact = SuggestionImpact.MEDIUM,
                        confidence = 0.5,
                        reasoning = content.take(500)
                    )
                ),
                confidence = 0.5,
                reasoning = "Failed to parse structured response: ${e.message}"
            )
        }
    }
    
    private fun extractSuggestions(jsonContent: String): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        
        try {
            val suggestionsMatch = Regex("\"suggestions\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
                .find(jsonContent)
            
            if (suggestionsMatch != null) {
                suggestions.add(
                    OptimizationSuggestion(
                        type = SuggestionType.OTHER,
                        description = "LLM-generated optimization suggestion",
                        originalText = null,
                        suggestedText = null,
                        impact = SuggestionImpact.MEDIUM,
                        confidence = 0.7,
                        reasoning = "Extracted from LLM response"
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract suggestions", e)
        }
        
        return suggestions
    }
    
    private fun extractConfidence(jsonContent: String): Double {
        return try {
            val confidenceMatch = Regex("\"confidence\"\\s*:\\s*([0-9.]+)").find(jsonContent)
            confidenceMatch?.groupValues?.get(1)?.toDouble() ?: 0.7
        } catch (e: Exception) {
            0.7
        }
    }
    
    private fun extractReasoning(jsonContent: String): String {
        return try {
            val reasoningMatch = Regex("\"reasoning\"\\s*:\\s*\"([^\"]+)\"").find(jsonContent)
            reasoningMatch?.groupValues?.get(1) ?: "LLM-based optimization analysis"
        } catch (e: Exception) {
            "LLM-based optimization analysis"
        }
    }
    
    private fun extractOptimizedPrompt(jsonContent: String): Prompt? {
        return try {
            val templateMatch = Regex("\"template\"\\s*:\\s*\"([^\"]+)\"").find(jsonContent)
            val template = templateMatch?.groupValues?.get(1)
            
            if (template != null) {
                Prompt(
                    id = "optimized_prompt",
                    name = "Optimized Prompt",
                    template = template,
                    variables = extractVariablesFromTemplate(template)
                )
            } else null
        } catch (e: Exception) {
            logger.warn("Failed to extract optimized prompt", e)
            null
        }
    }
    
    private fun extractVariablesFromTemplate(template: String): Set<String> {
        val variables = mutableSetOf<String>()
        val pattern = Regex("\\{\\{(\\w+)\\}\\}")
        pattern.findAll(template).forEach { match ->
            variables.add(match.groupValues[1])
        }
        return variables
    }
}

private data class ParsedOptimizationResponse(
    val optimizedPrompt: Prompt?,
    val suggestions: List<OptimizationSuggestion>,
    val confidence: Double,
    val reasoning: String
)