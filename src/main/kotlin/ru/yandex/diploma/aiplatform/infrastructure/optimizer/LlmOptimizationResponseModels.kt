package ru.yandex.diploma.aiplatform.infrastructure.optimizer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class LlmOptimizationEnvelopeDto(
    val suggestions: List<LlmOptimizationSuggestionDto> = emptyList(),
    val confidence: Double? = null,
    val reasoning: String? = null,
    val optimizedPrompt: LlmOptimizedPromptDto? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class LlmOptimizationSuggestionDto(
    val type: String? = null,
    val description: String = "",
    val originalText: String? = null,
    val suggestedText: String? = null,
    val impact: String? = null,
    val confidence: Double? = null,
    val reasoning: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class LlmOptimizedPromptDto(
    val id: String? = null,
    val name: String? = null,
    val template: String = "",
)

internal object OptimizationJsonExtractions {

    fun firstBalancedJsonObject(raw: CharSequence): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val ch = raw[i]
            if (!inString) {
                when (ch) {
                    '"' -> {
                        inString = true
                        escaped = false
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            } else {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            }
        }
        return null
    }

    fun stripFenceAndExtract(raw: String): String? {
        val fenced = Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE).find(raw)
        if (fenced != null) {
            val afterFence = raw.substring(fenced.range.last + 1)
            val endFence = Regex("```").find(afterFence)
            val inner = endFence?.let { afterFence.take(it.range.first).trim() } ?: afterFence.trim()
            val direct = firstBalancedJsonObject(inner)
            return direct ?: firstBalancedJsonObject(raw.trim())
        }
        return firstBalancedJsonObject(raw.trim())
    }
}
