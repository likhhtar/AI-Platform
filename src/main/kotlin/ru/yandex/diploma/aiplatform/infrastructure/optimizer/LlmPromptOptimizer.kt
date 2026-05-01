package ru.yandex.diploma.aiplatform.infrastructure.optimizer

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.model.extractTemplateVariables
import ru.yandex.diploma.aiplatform.domain.model.validateTemplatePlaceholderBagPreservation
import ru.yandex.diploma.aiplatform.domain.provider.LlmProviderException
import ru.yandex.diploma.aiplatform.domain.provider.ProviderRegistry
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizer

@Component
class LlmPromptOptimizer(
    private val providerRegistry: ProviderRegistry,
) : PromptOptimizer {

    private val logger = LoggerFactory.getLogger(LlmPromptOptimizer::class.java)

    private val jsonMapper =
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
        }

    override val optimizerType: OptimizerType = OptimizerType.LLM

    override suspend fun optimize(input: OptimizationInput, config: OptimizationConfig): OptimizationResult {
        val startTime = System.currentTimeMillis()
        val llmConfig = config.llmConfig
            ?: throw PromptOptimizationException("LLM config is required for LLM optimizer", optimizerType = optimizerType)
        val provider = providerRegistry.getProvider(llmConfig.provider)

        logger.info("LLM prompt optimization starting for promptId=${input.originalPrompt.id}")

        val systemPromptBuilt = buildJsonEnforcerSystemPrompt(llmConfig.systemPrompt)

        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val metaPrompt = buildMetaPrompt(input, config.mode, attempt)
                val temp =
                    when {
                        attempt == 0 -> llmConfig.temperature.coerceIn(0.05, 1.0)
                        else -> (llmConfig.temperature.coerceAtMost(0.3)).coerceAtLeast(0.05)
                    }

                val raw = provider.generate(
                    LlmRequest(
                        prompt = metaPrompt,
                        systemPrompt = systemPromptBuilt,
                        model = llmConfig.model,
                        temperature = temp,
                        maxTokens = llmConfig.maxTokens ?: 4096,
                        topP = 0.92,
                        frequencyPenalty = 0.0,
                        presencePenalty = 0.0,
                        additionalParameters = emptyMap(),
                    ),
                ).content

                val envelopeDto = deserializeEnvelope(raw)
                val mapped = mapEnvelopeToOptimizationFields(envelopeDto, raw, input, config.mode).let {
                    normalizeSuggestionConfidences(it)
                }

                mapped.optimizedPrompt?.let { finalized ->
                    if (config.mode == OptimizationMode.APPLY) {
                        try {
                            validateTemplatePlaceholderBagPreservation(
                                input.originalPrompt.template,
                                finalized.template,
                            )
                        } catch (e: IllegalArgumentException) {
                            logger.warn(
                                "APPLY template placeholder mismatch (attempt {}/{}): {}",
                                attempt + 1,
                                MAX_RETRIES,
                                e.message,
                            )
                            if (attempt >= MAX_RETRIES - 1) {
                                throw PromptOptimizationException(
                                    message = e.message ?: "Placeholder multiset mismatch",
                                    cause = e,
                                    optimizerType = optimizerType,
                                )
                            }
                            return@repeat
                        }
                    }
                }

                return buildOptimizationResult(startTime, input, llmConfig, mapped, raw, config.mode)
            } catch (e: PromptOptimizationException) {
                if (isRetryableOptimizerEnvelopeFailure(e) && attempt < MAX_RETRIES - 1) {
                    logger.warn(
                        "LLM optimizer envelope retryable failure (attempt {}/{}): {}",
                        attempt + 1,
                        MAX_RETRIES,
                        e.message,
                    )
                    return@repeat
                }
                throw e
            } catch (e: IllegalArgumentException) {
                throw PromptOptimizationException(
                    message = e.message ?: "Invalid LLM optimization argument",
                    cause = e,
                    optimizerType = optimizerType,
                )
            } catch (e: JsonProcessingException) {
                if (attempt < MAX_RETRIES - 1) {
                    logger.warn(
                        "LLM optimizer JSON parse retry (attempt {}/{}): {}",
                        attempt + 1,
                        MAX_RETRIES,
                        e.message,
                    )
                    return@repeat
                }
                throw PromptOptimizationException(
                    message = "Invalid JSON: ${e.message}",
                    cause = e,
                    optimizerType = optimizerType,
                )
            } catch (e: Exception) {
                if (!isTransientProviderFailure(e)) {
                    throw PromptOptimizationException(
                        message = "LLM optimization failed: ${e.message}",
                        cause = e,
                        optimizerType = optimizerType,
                    )
                }
                lastError = e
                logger.warn(
                    "LLM optimization attempt ${attempt + 1}/$MAX_RETRIES failed (transient): ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }

        val msg =
            lastError?.let { "LLM optimization exhausted retries (${it.javaClass.simpleName}: ${it.message})" }
                ?: "LLM optimization exhausted retries"

        throw PromptOptimizationException(message = msg, cause = lastError, optimizerType = optimizerType)
    }

    private fun deserializeEnvelope(raw: String): LlmOptimizationEnvelopeDto {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) {
            throw PromptOptimizationException(
                "No JSON object in LLM response (empty body)",
                optimizerType = optimizerType,
            )
        }

        val candidates = LinkedHashSet<String>()
        OptimizationJsonExtractions.stripFenceAndExtract(trimmed)?.let { candidates.add(it) }
        OptimizationJsonExtractions.firstBalancedJsonObject(trimmed)?.let { candidates.add(it) }
        candidates.addAll(OptimizationJsonExtractions.balancedJsonObjectsFrom(trimmed))

        if (candidates.isEmpty()) {
            throw PromptOptimizationException("No JSON object in LLM response", optimizerType = optimizerType)
        }

        var lastParseError: Exception? = null
        for (clipped in candidates) {
            try {
                return jsonMapper.readValue<LlmOptimizationEnvelopeDto>(clipped)
            } catch (e: Exception) {
                lastParseError = e
            }
        }

        throw PromptOptimizationException(
            message = "Invalid JSON envelope from LLM: ${lastParseError?.message}",
            cause = lastParseError,
            optimizerType = optimizerType,
        )
    }

    private fun isRetryableOptimizerEnvelopeFailure(e: PromptOptimizationException): Boolean {
        val m = e.message ?: return false
        return m.contains("No JSON object in LLM response", ignoreCase = true) ||
            m.contains("Invalid JSON envelope from LLM", ignoreCase = true) ||
            m.startsWith("Invalid JSON:") ||
            m.contains("optimizedPrompt missing", ignoreCase = true) ||
            m.contains("optimizedPrompt.template blank", ignoreCase = true)
    }

    private fun mapEnvelopeToOptimizationFields(
        dto: LlmOptimizationEnvelopeDto,
        raw: String,
        input: OptimizationInput,
        mode: OptimizationMode,
    ): MappedOptimizationFields {
        val suggestions =
            dto.suggestions.map { suggestionDto ->
                OptimizationSuggestion(
                    type =
                        kotlin.runCatching {
                            SuggestionType.valueOf(suggestionDto.type?.trim()?.uppercase() ?: "OTHER")
                        }.getOrDefault(SuggestionType.OTHER),
                    description = suggestionDto.description.ifBlank { "Suggestion" },
                    originalText = suggestionDto.originalText?.takeUnless { it.isBlank() },
                    suggestedText = suggestionDto.suggestedText?.takeUnless { it.isBlank() },
                    impact = kotlin.runCatching {
                        SuggestionImpact.valueOf(suggestionDto.impact?.trim()?.uppercase() ?: "MEDIUM")
                    }.getOrDefault(SuggestionImpact.MEDIUM),
                    confidence = suggestionDto.confidence?.coerceIn(0.0, 1.0) ?: 0.72,
                    reasoning = (suggestionDto.reasoning ?: "").ifBlank {
                        "(no per-suggestion reasoning)"
                    },
                )
            }

        val reasoningOverall =
            dto.reasoning?.trim()?.takeUnless { it.isBlank() }
                ?: if (dto.optimizedPrompt != null) {
                    "Structured optimization (${raw.length} chars)"
                } else {
                    "${suggestions.size} structured suggestions (${raw.length} chars)"
                }

        val envConfidence =
            dto.confidence?.coerceIn(0.0, 1.0)
                ?: run {
                    when {
                        suggestions.isNotEmpty() -> suggestions.map { it.confidence }.average()
                        dto.optimizedPrompt != null -> 0.75
                        else -> 0.5
                    }
                }

        val optimizedPrompt =
            when (mode) {
                OptimizationMode.SUGGEST -> null
                OptimizationMode.APPLY -> {
                    val p =
                        dto.optimizedPrompt ?: throw PromptOptimizationException(
                            "optimizedPrompt missing in APPLY mode",
                            optimizerType = optimizerType,
                        )
                    if (p.template.isBlank()) {
                        throw PromptOptimizationException(
                            message = "optimizedPrompt.template blank",
                            optimizerType = optimizerType,
                        )
                    }
                    finalizePromptPreserveId(original = input.originalPrompt, dto = p)
                }
            }

        return MappedOptimizationFields(
            optimizedPrompt = optimizedPrompt,
            suggestions = suggestions,
            confidence = envConfidence.coerceIn(0.0, 1.0),
            reasoning = reasoningOverall,
        )
    }

    private data class MappedOptimizationFields(
        val optimizedPrompt: Prompt?,
        val suggestions: List<OptimizationSuggestion>,
        val confidence: Double,
        val reasoning: String,
    )

    private fun normalizeSuggestionConfidences(fields: MappedOptimizationFields): MappedOptimizationFields =
        fields.copy(
            confidence = fields.confidence.coerceIn(0.0, 1.0),
            suggestions =
                fields.suggestions.map { s ->
                    s.copy(confidence = s.confidence.coerceIn(0.0, 1.0))
                },
        )

    private fun finalizePromptPreserveId(original: Prompt, dto: LlmOptimizedPromptDto): Prompt {
        val template = dto.template
        val vars =
            extractTemplateVariables(template).ifEmpty { original.variables }

        val name =
            dto.name?.trim()?.takeUnless { it.isBlank() } ?: "${original.name} (optimized)"

        val id = original.id
        return Prompt(id = id, name = name, template = template, variables = vars)
    }

    private fun buildOptimizationResult(
        startMs: Long,
        input: OptimizationInput,
        llmConfig: LlmOptimizerConfig,
        mapped: MappedOptimizationFields,
        rawSnippet: String,
        mode: OptimizationMode,
    ): OptimizationResult {
        val executionTimeMs = System.currentTimeMillis() - startMs
        val provisionalStatus =
            when (mode) {
                OptimizationMode.SUGGEST -> OptimizationStatus.SUGGESTED
                OptimizationMode.APPLY ->
                    if (mapped.optimizedPrompt != null) {
                        OptimizationStatus.APPLIED
                    } else {
                        OptimizationStatus.FAILED
                    }
            }
        return OptimizationResult(
            originalPrompt = input.originalPrompt,
            optimizedPrompt = mapped.optimizedPrompt,
            suggestions = mapped.suggestions,
            confidence = mapped.confidence,
            reasoning = mapped.reasoning,
            metadata =
                mapOf(
                    "provider" to llmConfig.provider,
                    "model" to llmConfig.model,
                    "averageScore" to input.testResults.map { it.evaluationResult.score }.average(),
                    "llmSnippetChars" to rawSnippet.length.coerceAtMost(8192),
                    "optimizationStatus" to provisionalStatus.name,
                ),
            executionTimeMs = executionTimeMs,
            status = provisionalStatus,
        )
    }

    private fun buildJsonEnforcerSystemPrompt(userExtra: String?): String =
        buildString {
            appendLine(
                "You MUST reply with ONLY a single JSON OBJECT (RFC 8259). No prose, no markdown fences, no BOM.",
            )
            appendLine(
                """Escape newline characters INSIDE strings as \n do NOT break JSON validity.""",
            )
            appendLine(
                """In APPLY mode rewrite the FULL template body but NEVER rename, omit, add, or refactor {{placeholder}} identifiers — same multiset of names and occurrence counts as the benchmark ({{x}} twice must stay twice).""",
            )
            appendLine(
                """Do not remove structural sections (numbered lists, output format headings) unless the failure analysis clearly demands a minimal clarification.""",
            )
            userExtra?.takeIf { it.isNotBlank() }?.trim()?.also { appendLine(it) }
        }.trim()

    private fun buildMetaPrompt(input: OptimizationInput, mode: OptimizationMode, attempt: Int): String {
        val overrideBody = input.metaPromptOverride?.takeIf { it.isNotBlank() }
        if (overrideBody != null) {
            return buildString {
                appendLine("# Prompt optimization (${mode})")
                when (attempt) {
                    1 -> appendLine("(Retry: conform to JSON strictly; placeholders must remain identical.)")
                    else -> {}
                }
                if (attempt >= 2) {
                    appendLine("(Last-chance retry: fix JSON quoting only copy template placeholders verbatim.)")
                }
                appendLine(overrideBody)
                appendLine()
                val varsFromTpl = extractTemplateVariables(input.originalPrompt.template)
                val canonical = varsFromTpl.ifEmpty { input.originalPrompt.variables }
                appendLine(
                    "## Preserve these template variables verbatim in APPLY mode (same spelling): `${canonical.joinToString()}`",
                )
                appendLine()
                appendLine("# JSON schema you MUST obey")
                appendLine(schemaDescription(mode, attempt))
            }.trim()
        }
        return buildString {
            appendLine("# Prompt optimization (${mode})")
            when (attempt) {
                1 -> appendLine("(Retry: conform to JSON strictly; placeholders must remain identical.)")
                else -> {}
            }
            if (attempt >= 2) appendLine("(Last-chance retry: fix JSON quoting only copy template placeholders verbatim.)")

            appendLine("## Prompt template `${input.originalPrompt.id}`")
            appendLine("```txt")
            appendLine(input.originalPrompt.template)
            appendLine("```")

            val varsFromTpl = extractTemplateVariables(input.originalPrompt.template)
            val canonical =
                varsFromTpl.ifEmpty { input.originalPrompt.variables }
            appendLine("## Preserve these template variables verbatim in APPLY mode (same spelling): `${canonical.joinToString()}`")

            appendLine(
                "## Agent: `${clip(input.agentConfig.name, 80)}`, model=`${input.agentConfig.model ?: "?"}`, t=${input.agentConfig.temperature}",
            )

            appendLine()
            appendLine("## Harness results per test (${input.testResults.size})")
            appendLine("(Use **expected**, **actual output**, **evaluator rationale** to localize failures.)")
            input.testResults.forEachIndexed { i, r ->
                val tc = r.testCase
                appendLine("### #$i `${tc.promptId}`")
                appendLine("- **score** ${r.evaluationResult.score}  **passed** ${r.evaluationResult.passed}")
                appendLine("- **expected (test oracle):**")
                appendLine(clippedFence(clip(tc.expected, 1800)))
                appendLine("- **actual (model output):**")
                appendLine(clippedFence(clip(r.llmResponse?.content ?: "(missing — infrastructure or provider error)", 1800)))
                appendLine("- **evaluator rationale:**")
                appendLine(clippedFence(clip(r.evaluationResult.explanation, 1200)))
                appendLine()
            }

            appendLine("# JSON schema you MUST obey")
            appendLine(schemaDescription(mode, attempt))
        }.trim()
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) {
            s
        } else {
            "${s.take(max)} … [truncated ${s.length - max} chars]"
        }

    private fun clippedFence(body: String): String =
        "```txt\n$body\n```"

    private fun schemaDescription(mode: OptimizationMode, @Suppress("UNUSED_PARAMETER") attempt: Int): String =
        buildString {
            appendLine("{")
            appendLine("  \"confidence\": number (0..1),")
            appendLine(
                "  \"reasoning\": string (overall: how failures relate to prompt gaps; cite which tests improved),",
            )
            appendLine("  \"suggestions\": [ {")
            appendLine("       \"type\": \"CLARITY|SPECIFICITY|LENGTH|STRUCTURE|CONTEXT|EXAMPLES|CONSTRAINTS|FORMAT|TONE|OTHER\",")
            appendLine("       \"description\": string,")
            appendLine("       \"originalText\": string optional, \"suggestedText\": string optional,")
            appendLine("       \"impact\": \"LOW|MEDIUM|HIGH|CRITICAL\",")
            appendLine("       \"confidence\": number 0..1,")
            appendLine("       \"reasoning\": string")
            appendLine("  }]")
            when (mode) {
                OptimizationMode.APPLY -> {
                    appendLine(" ,")
                    appendLine("  \"optimizedPrompt\": {")
                    appendLine("       \"template\": \"REQUIRED: full rewritten template JSON string\",")
                    appendLine(
                        "       \"name\": \"short label optional (prompt id is pinned from YAML)\"",
                    )
                    appendLine("  }")
                    appendLine("  Ignore prompt \"id\" in output — the harness pins the YAML id.")
                }
                OptimizationMode.SUGGEST ->
                    appendLine("  Do NOT include \"optimizedPrompt\".")
            }
            appendLine("}")
        }.trim()

    override suspend fun isAvailable(): Boolean =
        kotlin.runCatching { providerRegistry.getAvailableProviders().isNotEmpty() }.getOrElse { false }

    override fun getConfigurationRequirements(): List<String> =
        listOf(
            "llmConfig.provider",
            "llmConfig.model",
            "Optional: llmConfig.temperature, llmConfig.maxTokens, llmConfig.systemPrompt",
        )

    private fun isTransientProviderFailure(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            when (current) {
                is WebClientResponseException -> {
                    val code = current.statusCode.value()
                    return code == HttpStatus.TOO_MANY_REQUESTS.value() ||
                        code == HttpStatus.REQUEST_TIMEOUT.value() ||
                        code in 500..599
                }
                is WebClientRequestException -> return true
                is SocketTimeoutException -> return true
                is java.net.ConnectException -> return true
                is TimeoutCancellationException -> return true
            }
            current = current.cause
        }
        if (e is LlmProviderException) {
            val msg = e.message?.lowercase().orEmpty()
            return msg.contains("rate limit") ||
                msg.contains("try again later") ||
                msg.contains("server error") ||
                msg.contains("timeout") ||
                msg.contains("timed out")
        }
        return false
    }

    companion object {
        private const val MAX_RETRIES = 5
    }
}
