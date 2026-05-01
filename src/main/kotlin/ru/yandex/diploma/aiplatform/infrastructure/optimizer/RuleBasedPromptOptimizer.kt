package ru.yandex.diploma.aiplatform.infrastructure.optimizer

import java.util.regex.Matcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.model.containsMatchOutsidePlaceholderSpans
import ru.yandex.diploma.aiplatform.domain.model.extractTemplateVariables
import ru.yandex.diploma.aiplatform.domain.model.missingTemplatePlaceholders
import ru.yandex.diploma.aiplatform.domain.model.replaceRegexOutsidePlaceholderSpans
import ru.yandex.diploma.aiplatform.domain.model.transformOutsidePlaceholderSpans
import ru.yandex.diploma.aiplatform.domain.model.validateTemplatePlaceholderBagPreservation
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizer

@Component
class RuleBasedPromptOptimizer : PromptOptimizer {

    private val logger = LoggerFactory.getLogger(RuleBasedPromptOptimizer::class.java)

    override val optimizerType: OptimizerType = OptimizerType.RULE_BASED

    override suspend fun optimize(input: OptimizationInput, config: OptimizationConfig): OptimizationResult {
        val start = System.currentTimeMillis()
        try {
            logger.info("Rule-based optimization for prompt `${input.originalPrompt.id}` mode=${config.mode}")

            val rc = config.ruleBasedConfig ?: RuleBasedOptimizerConfig()
            val failures = input.testResults.count { !it.evaluationResult.passed }
            val lowScores = input.testResults.count { it.evaluationResult.score < 0.7 }

            val ctx =
                RuleContext(
                    original = input.originalPrompt,
                    failures = failures,
                    lowScores = lowScores,
                )

            val staged =
                mutableListOf<(String, RuleContext, RuleBasedOptimizerConfig) -> Pair<String, String>>()
            if (rc.enableClarityOptimization) staged.add { t, c, r -> stripVagueLanguage(t, c, r) }
            if (rc.enableSpecificityOptimization) {
                staged.add { t, c, r -> injectOutputContract(t, c, r) }
                staged.add { t, c, r -> injectJsonOnlyIfRequestedOrHeuristic(t, c, r) }
                staged.add { t, c, r -> addFailureDrivenConstraints(t, c, r) }
            }
            if (rc.enableLengthOptimization) staged.add { t, c, r -> balanceLength(t, c, r) }

            val originalTemplate = input.originalPrompt.template
            var working = originalTemplate
            val appliedRuleNames = mutableListOf<String>()
            for (stage in staged) {
                val (next, label) = stage(working, ctx, rc)
                if (next == working) continue
                val missing = missingTemplatePlaceholders(originalTemplate, next)
                if (missing.isNotEmpty()) {
                    logger.warn(
                        "Skipping rule-based stage '{}': would drop template placeholders {}",
                        label,
                        missing,
                    )
                    continue
                }
                appliedRuleNames += label
                working = next
            }

            working = applyConfigurableRegexRules(originalTemplate, working, rc.rules, appliedRuleNames)

            try {
                validateTemplatePlaceholderBagPreservation(input.originalPrompt.template, working)
            } catch (e: IllegalArgumentException) {
                throw PromptOptimizationException(
                    message = e.message ?: "Rule-based optimizer corrupted template placeholders",
                    cause = e,
                    optimizerType = optimizerType,
                )
            }

            val suggestions = buildStructuredSuggestions(
                input = input,
                ctx = ctx,
                appliedRuleNames = appliedRuleNames,
            )

            val vars = extractTemplateVariables(working).ifEmpty { input.originalPrompt.variables }
            val optimized =
                if (config.mode == OptimizationMode.APPLY) {
                    Prompt(
                        id = input.originalPrompt.id,
                        name = "${input.originalPrompt.name} (rule-optimized)",
                        template = working,
                        variables = vars,
                    )
                } else {
                    null
                }

            val confidence =
                computeAggregateConfidence(
                    appliedRuleNames = appliedRuleNames,
                    failureRate = failures.toDouble() / input.testResults.size.coerceAtLeast(1),
                    lowScoreRate = lowScores.toDouble() / input.testResults.size.coerceAtLeast(1),
                )

            val exec = System.currentTimeMillis() - start
            val provisionalStatus =
                when (config.mode) {
                    OptimizationMode.SUGGEST -> OptimizationStatus.SUGGESTED
                    OptimizationMode.APPLY ->
                        if (optimized != null) OptimizationStatus.APPLIED else OptimizationStatus.FAILED
                }
            return OptimizationResult(
                originalPrompt = input.originalPrompt,
                optimizedPrompt = optimized,
                suggestions = suggestions,
                confidence = confidence.coerceIn(0.0, 1.0),
                reasoning =
                    buildReasoning(
                        failures = failures,
                        lowScores = lowScores,
                        applied = appliedRuleNames,
                    ),
                metadata =
                    mapOf(
                        "stages" to appliedRuleNames.size,
                        "stageNames" to appliedRuleNames,
                        "optimizationStatus" to provisionalStatus.name,
                    ),
                executionTimeMs = exec,
                status = provisionalStatus,
            )
        } catch (e: PromptOptimizationException) {
            throw e
        } catch (e: Exception) {
            throw PromptOptimizationException(
                message = "Rule-based optimization failed: ${e.message}",
                cause = e,
                optimizerType = optimizerType,
            )
        }
    }

    private data class RuleContext(
        val original: Prompt,
        val failures: Int,
        val lowScores: Int,
    )

    private fun stripVagueLanguage(
        tpl: String,
        @Suppress("UNUSED_PARAMETER") ctx: RuleContext,
        @Suppress("UNUSED_PARAMETER") rc: RuleBasedOptimizerConfig,
    ): Pair<String, String> =
        stripVagueLanguageInPlainTextSegments(tpl) to "stripVagueLanguageOutsideCodeFences"

    private fun stripVagueLanguageInPlainTextSegments(tpl: String): String =
        transformOutsideTripleBacktickRegions(tpl) { segment ->
            transformOutsidePlaceholderSpans(segment) { plain ->
                var next = plain
                val pairs =
                    listOf(
                        Regex("""\b(something|somewhat|maybe|perhaps|kind of|sort of)\b""", RegexOption.IGNORE_CASE) to "",
                        Regex("""(что-то|как-то|возможно|наверное|вроде бы)""", RegexOption.IGNORE_CASE) to "",
                    )
                for ((re, replacement) in pairs) {
                    next = re.replace(next, Matcher.quoteReplacement(replacement))
                }
                next
            }
        }

    private fun transformOutsideTripleBacktickRegions(template: String, transform: (String) -> String): String {
        if (!template.contains("```")) return transform(template)
        val out = StringBuilder()
        var i = 0
        while (i <= template.lastIndex) {
            val fenceStart = template.indexOf("```", i)
            if (fenceStart < 0) {
                out.append(transform(template.substring(i)))
                break
            }
            out.append(transform(template.substring(i, fenceStart)))
            val fenceEnd = template.indexOf("```", fenceStart + 3)
            if (fenceEnd < 0) {
                out.append(template.substring(fenceStart))
                break
            }
            out.append(template.substring(fenceStart, fenceEnd + 3))
            i = fenceEnd + 3
        }
        return out.toString()
    }

    private fun injectOutputContract(
        tpl: String,
        ctx: RuleContext,
        @Suppress("UNUSED_PARAMETER") rc: RuleBasedOptimizerConfig,
    ): Pair<String, String> {
        val marker = "<!-- rule:output-contract -->"
        if (tpl.contains(marker) ||
            Regex("""(?i)(output contract|контракт вывода)""").containsMatchIn(tpl)
        ) {
            return tpl to "skipOutputContract"
        }
        val block =
            buildString {
                appendLine()
                appendLine(marker)
                appendLine("### Контракт вывода")
                appendLine("Сначала ответь на запрос пользователя.")
                appendLine("Держи ответ кратким, если явно не требуется больше деталей.")
                if (ctx.failures > 0) {
                    appendLine("При тестоподобных требованиях предпочитай точность креативности.")
                }
            }
        return (tpl.trimEnd() + "\n" + block.trim()) to "injectOutputContract"
    }

    private fun injectJsonOnlyIfRequestedOrHeuristic(
        tpl: String,
        ctx: RuleContext,
        @Suppress("UNUSED_PARAMETER") rc: RuleBasedOptimizerConfig,
    ): Pair<String, String> {
        val jsonHint = Regex("""(?i)(json|schema|array|object)\b""").containsMatchIn(tpl + ctx.original.name)
        val testJson =
            ctx.failures > 0 &&
                containsMatchOutsidePlaceholderSpans(tpl, Regex("""[{}\[\]"]"""))
        if (!jsonHint && !testJson) return tpl to "skipJsonOnly"
        if (Regex("""(?i)(only json|строго json)""").containsMatchIn(tpl)) return tpl to "alreadyJsonOnly"
        val block =
            """
            |### Структурированный вывод
            |Если ответ должен быть машиночитаемым, отвечай ТОЛЬКО ВАЛИДНЫМ JSON, без markdown-ограждений и без комментариев до/после.
            """.trimMargin()
        return (tpl.trimEnd() + "\n" + block) to "injectStructuredJsonRule"
    }

    private fun addFailureDrivenConstraints(
        tpl: String,
        ctx: RuleContext,
        @Suppress("UNUSED_PARAMETER") rc: RuleBasedOptimizerConfig,
    ): Pair<String, String> {
        if (ctx.failures <= 1 && ctx.lowScores <= 1) return tpl to "skipHardConstraints"
        val marker = "<!-- rule:constraints -->"
        if (tpl.contains(marker) ||
            Regex("""(?i)(### constraints|### ограничения)""").containsMatchIn(tpl)
        ) {
            return tpl to "alreadyHasNegatives"
        }
        val block =
            """
            |$marker
            |### Ограничения
            |- Не противоречь предыдущим инструкциям.
            |- Если не уверен, явно укажи допущения в одном коротком предложении.
            """.trimMargin()
        return (tpl.trimEnd() + "\n" + block) to "failureDrivenConstraints"
    }

    private fun balanceLength(
        tpl: String,
        @Suppress("UNUSED_PARAMETER") ctx: RuleContext,
        @Suppress("UNUSED_PARAMETER") rc: RuleBasedOptimizerConfig,
    ): Pair<String, String> {
        if (tpl.length < 1200) return tpl to "skipBalanceLength"
        if (tpl.contains("```")) return tpl to "skipBalanceLengthCodeFences"
        val lines = tpl.lines()
        val withHeadings =
            buildString {
                appendLine("### Основные инструкции")
                lines.take(40).joinTo(this, separator = "\n")
                appendLine()
                appendLine("### Дополнительный контекст")
                lines.drop(40).joinTo(this, separator = "\n")
            }
        return withHeadings to "sectionRewriteLongPrompt"
    }

    private fun applyConfigurableRegexRules(
        originalTemplate: String,
        template: String,
        rules: List<OptimizationRule>,
        audit: MutableList<String>,
    ): String =
        rules.filter { it.enabled }.fold(template) { acc, rule ->
            if (rule.pattern.isBlank()) {
                logger.warn("Skip regex rule with empty pattern name={}", rule.name)
                return@fold acc
            }
            try {
                val pattern = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                val safeReplacement = Matcher.quoteReplacement(rule.replacement)
                val next = replaceRegexOutsidePlaceholderSpans(acc, pattern, safeReplacement)
                val missing = missingTemplatePlaceholders(originalTemplate, next)
                if (missing.isNotEmpty()) {
                    logger.warn(
                        "Skipping regex rule '{}': would drop template placeholders {}",
                        rule.name,
                        missing,
                    )
                    return@fold acc
                }
                if (next != acc) {
                    audit += "regex:${rule.name}"
                    logger.info("Regex rule '{}' applied (pattern chars={})", rule.name, rule.pattern.length)
                }
                next
            } catch (ex: Exception) {
                logger.warn("Skip invalid regex rule {}", rule.name, ex)
                acc
            }
        }

    private fun buildStructuredSuggestions(
        input: OptimizationInput,
        ctx: RuleContext,
        appliedRuleNames: List<String>,
    ): List<OptimizationSuggestion> {
        val hints = mutableListOf<OptimizationSuggestion>()
        hints +=
            OptimizationSuggestion(
                type = SuggestionType.STRUCTURE,
                description =
                    "${appliedRuleNames.size} deterministic transforms applied (${appliedRuleNames.take(8).joinToString()})",
                originalText = null,
                suggestedText = null,
                impact = if (appliedRuleNames.isEmpty()) SuggestionImpact.LOW else SuggestionImpact.MEDIUM,
                confidence = if (appliedRuleNames.isEmpty()) 0.45 else 0.78,
                reasoning = "Rule pipeline executed on `${input.originalPrompt.id}`",
            )

        if (ctx.failures > 0) {
            hints +=
                OptimizationSuggestion(
                    type = SuggestionType.CONSTRAINTS,
                    description = "${ctx.failures} failing evaluations, tighten instructions & output contract",
                    originalText = null,
                    suggestedText = null,
                    impact = SuggestionImpact.HIGH,
                    confidence = 0.74,
                    reasoning = "Failure signal from benchmark harness",
                )
        }
        return hints
    }

    private fun computeAggregateConfidence(
        appliedRuleNames: List<String>,
        failureRate: Double,
        lowScoreRate: Double,
    ): Double {
        val base = 0.45 + appliedRuleNames.size * 0.05 + failureRate * 0.2 + lowScoreRate * 0.1
        return base.coerceIn(0.3, 0.93)
    }

    private fun buildReasoning(
        failures: Int,
        lowScores: Int,
        applied: List<String>,
    ): String =
        buildString {
            appendLine(
                """Rule optimizer applied deterministic transforms before optional regex substitutions.""",
            )
            appendLine("failures watched: $failures; low-score: $lowScores")
            appendLine("transforms: ${applied.joinToString(limit = 20)}")
        }.trim()

    override suspend fun isAvailable(): Boolean = true

    override fun getConfigurationRequirements(): List<String> =
        listOf(
            "Optional ruleBasedConfig with enable* toggles",
            "Optional regex OptimizationRule replacements",
        )
}
