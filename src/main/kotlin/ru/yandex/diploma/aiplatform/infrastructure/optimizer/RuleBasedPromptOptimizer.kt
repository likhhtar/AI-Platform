package ru.yandex.diploma.aiplatform.infrastructure.optimizer

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizer
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException

@Component
class RuleBasedPromptOptimizer : PromptOptimizer {
    
    private val logger = LoggerFactory.getLogger(RuleBasedPromptOptimizer::class.java)
    
    override val optimizerType: OptimizerType = OptimizerType.RULE_BASED
    
    override suspend fun optimize(input: OptimizationInput, config: OptimizationConfig): OptimizationResult {
        val startTime = System.currentTimeMillis()
        
        try {
            logger.info("Starting rule-based prompt optimization for prompt: ${input.originalPrompt.id}")
            
            val ruleConfig = config.ruleBasedConfig ?: RuleBasedOptimizerConfig()
            val suggestions = mutableListOf<OptimizationSuggestion>()
            
            val failedTests = input.testResults.filter { !it.evaluationResult.passed }
            val lowScoreTests = input.testResults.filter { it.evaluationResult.score < 0.7 }
            
            if (ruleConfig.enableLengthOptimization) {
                suggestions.addAll(analyzeLengthIssues(input.originalPrompt, failedTests))
            }
            
            if (ruleConfig.enableClarityOptimization) {
                suggestions.addAll(analyzeClarityIssues(input.originalPrompt, failedTests))
            }
            
            if (ruleConfig.enableSpecificityOptimization) {
                suggestions.addAll(analyzeSpecificityIssues(input.originalPrompt, failedTests))
            }
            
            suggestions.addAll(applyCustomRules(input.originalPrompt, ruleConfig.rules))
            
            val optimizedPrompt = if (config.mode == OptimizationMode.APPLY) {
                generateOptimizedPrompt(input.originalPrompt, suggestions)
            } else null
            
            val confidence = calculateConfidence(suggestions, failedTests.size, input.testResults.size)
            val reasoning = buildReasoning(suggestions, failedTests, lowScoreTests)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            logger.info("Rule-based optimization completed in ${executionTime}ms with ${suggestions.size} suggestions")
            
            return OptimizationResult(
                originalPrompt = input.originalPrompt,
                optimizedPrompt = optimizedPrompt,
                suggestions = suggestions,
                confidence = confidence,
                reasoning = reasoning,
                metadata = mapOf(
                    "optimizerType" to "rule-based",
                    "rulesApplied" to ruleConfig.rules.size,
                    "failedTestsCount" to failedTests.size,
                    "lowScoreTestsCount" to lowScoreTests.size,
                    "mode" to config.mode.name
                ),
                executionTimeMs = executionTime
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            logger.error("Rule-based optimization failed", e)
            throw PromptOptimizationException(
                "Rule-based optimization failed: ${e.message}",
                cause = e,
                optimizerType = optimizerType
            )
        }
    }
    
    override suspend fun isAvailable(): Boolean = true
    
    override fun getConfigurationRequirements(): List<String> {
        return listOf(
            "ruleBasedConfig.rules - List of custom optimization rules (optional)",
            "ruleBasedConfig.enableLengthOptimization - Enable length analysis (optional, default: true)",
            "ruleBasedConfig.enableClarityOptimization - Enable clarity analysis (optional, default: true)",
            "ruleBasedConfig.enableSpecificityOptimization - Enable specificity analysis (optional, default: true)"
        )
    }
    
    private fun analyzeLengthIssues(prompt: Prompt, failedTests: List<TestResult>): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        
        val template = prompt.template
        
        if (template.length < 50) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.LENGTH,
                    description = "Prompt appears too short and may lack sufficient context",
                    originalText = template,
                    suggestedText = null,
                    impact = SuggestionImpact.MEDIUM,
                    confidence = 0.7,
                    reasoning = "Short prompts (< 50 characters) often lack necessary context for clear instructions"
                )
            )
        }
        
        if (template.length > 1000) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.LENGTH,
                    description = "Prompt appears too long and may be overwhelming",
                    originalText = template,
                    suggestedText = null,
                    impact = SuggestionImpact.MEDIUM,
                    confidence = 0.6,
                    reasoning = "Very long prompts (> 1000 characters) can be difficult to follow and may confuse the model"
                )
            )
        }
        
        return suggestions
    }
    
    private fun analyzeClarityIssues(prompt: Prompt, failedTests: List<TestResult>): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        val template = prompt.template.lowercase()
        
        val vagueWords = listOf("что-то", "как-то", "возможно", "может быть", "наверное", "вроде")
        vagueWords.forEach { word ->
            if (template.contains(word)) {
                suggestions.add(
                    OptimizationSuggestion(
                        type = SuggestionType.CLARITY,
                        description = "Remove vague language: '$word'",
                        originalText = word,
                        suggestedText = null,
                        impact = SuggestionImpact.MEDIUM,
                        confidence = 0.8,
                        reasoning = "Vague words can make instructions unclear and lead to inconsistent responses"
                    )
                )
            }
        }
        
        if (!template.endsWith(".") && !template.endsWith("!") && !template.endsWith("?") && !template.endsWith(":")) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.CLARITY,
                    description = "Add proper punctuation at the end of the prompt",
                    originalText = null,
                    suggestedText = null,
                    impact = SuggestionImpact.LOW,
                    confidence = 0.6,
                    reasoning = "Proper punctuation helps clarify the end of instructions"
                )
            )
        }
        
        return suggestions
    }
    
    private fun analyzeSpecificityIssues(prompt: Prompt, failedTests: List<TestResult>): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        val template = prompt.template.lowercase()
        
        if (!template.contains("пример") && !template.contains("например") && failedTests.isNotEmpty()) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.EXAMPLES,
                    description = "Consider adding examples to clarify expected output format",
                    originalText = null,
                    suggestedText = null,
                    impact = SuggestionImpact.HIGH,
                    confidence = 0.7,
                    reasoning = "Examples help models understand the expected output format and style"
                )
            )
        }
        
        if (!template.contains("формат") && !template.contains("ответ") && !template.contains("результат")) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.FORMAT,
                    description = "Specify the expected output format",
                    originalText = null,
                    suggestedText = null,
                    impact = SuggestionImpact.MEDIUM,
                    confidence = 0.6,
                    reasoning = "Clear output format specifications help ensure consistent responses"
                )
            )
        }
        
        if (failedTests.size > 1 && !template.contains("только") && !template.contains("не") && !template.contains("избегай")) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.CONSTRAINTS,
                    description = "Consider adding constraints to prevent unwanted behaviors",
                    originalText = null,
                    suggestedText = null,
                    impact = SuggestionImpact.MEDIUM,
                    confidence = 0.5,
                    reasoning = "Constraints help prevent the model from producing unwanted outputs"
                )
            )
        }
        
        return suggestions
    }
    
    private fun applyCustomRules(prompt: Prompt, rules: List<OptimizationRule>): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        
        rules.filter { it.enabled }.forEach { rule ->
            try {
                val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(prompt.template)) {
                    suggestions.add(
                        OptimizationSuggestion(
                            type = SuggestionType.OTHER,
                            description = rule.description,
                            originalText = rule.pattern,
                            suggestedText = rule.replacement,
                            impact = SuggestionImpact.MEDIUM,
                            confidence = 0.8,
                            reasoning = "Custom rule: ${rule.name}"
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("Failed to apply custom rule: ${rule.name}", e)
            }
        }
        
        return suggestions
    }
    
    private fun generateOptimizedPrompt(originalPrompt: Prompt, suggestions: List<OptimizationSuggestion>): Prompt {
        var optimizedTemplate = originalPrompt.template
        
        suggestions
            .filter { it.impact == SuggestionImpact.HIGH || it.impact == SuggestionImpact.CRITICAL }
            .filter { it.originalText != null && it.suggestedText != null }
            .forEach { suggestion ->
                optimizedTemplate = optimizedTemplate.replace(
                    suggestion.originalText!!,
                    suggestion.suggestedText!!,
                    ignoreCase = true
                )
            }
        
        if (suggestions.any { it.type == SuggestionType.EXAMPLES }) {
            optimizedTemplate += "\n\nПример: [добавьте соответствующий пример]"
        }
        
        if (suggestions.any { it.type == SuggestionType.FORMAT }) {
            optimizedTemplate += "\n\nФормат ответа: [укажите желаемый формат]"
        }
        
        return Prompt(
            id = "${originalPrompt.id}_optimized",
            name = "${originalPrompt.name} (Optimized)",
            template = optimizedTemplate,
            variables = extractVariablesFromTemplate(optimizedTemplate)
        )
    }
    
    private fun extractVariablesFromTemplate(template: String): Set<String> {
        val variables = mutableSetOf<String>()
        val pattern = Regex("\\{\\{(\\w+)\\}\\}")
        pattern.findAll(template).forEach { match ->
            variables.add(match.groupValues[1])
        }
        return variables
    }
    
    private fun calculateConfidence(
        suggestions: List<OptimizationSuggestion>,
        failedTestsCount: Int,
        totalTestsCount: Int
    ): Double {
        if (suggestions.isEmpty()) return 0.3
        
        val avgSuggestionConfidence = suggestions.map { it.confidence }.average()
        val failureRate = failedTestsCount.toDouble() / totalTestsCount
        
        val impactBonus = suggestions.count { it.impact == SuggestionImpact.HIGH || it.impact == SuggestionImpact.CRITICAL } * 0.1
        val failureBonus = failureRate * 0.2
        
        return (avgSuggestionConfidence + impactBonus + failureBonus).coerceIn(0.0, 1.0)
    }
    
    private fun buildReasoning(
        suggestions: List<OptimizationSuggestion>,
        failedTests: List<TestResult>,
        lowScoreTests: List<TestResult>
    ): String {
        return buildString {
            appendLine("Rule-based analysis identified ${suggestions.size} optimization opportunities:")
            
            if (failedTests.isNotEmpty()) {
                appendLine("- ${failedTests.size} failed tests indicate potential issues with prompt clarity or specificity")
            }
            
            if (lowScoreTests.isNotEmpty()) {
                appendLine("- ${lowScoreTests.size} low-scoring tests suggest room for improvement")
            }
            
            val highImpactSuggestions = suggestions.filter { it.impact == SuggestionImpact.HIGH || it.impact == SuggestionImpact.CRITICAL }
            if (highImpactSuggestions.isNotEmpty()) {
                appendLine("- ${highImpactSuggestions.size} high-impact suggestions identified")
            }
            
            val suggestionsByType = suggestions.groupBy { it.type }
            suggestionsByType.forEach { (type, typeSuggestions) ->
                appendLine("- ${typeSuggestions.size} ${type.name.lowercase()} improvements suggested")
            }
        }
    }
}