package ru.yandex.diploma.aiplatform.application.optimization

internal object OptimizedPromptResponseParser {

    private val JSON_FENCE_REGEX = Regex("""```\s*json\b""", RegexOption.IGNORE_CASE)
    private val JSON_OBJECT_START_REGEX = Regex("""\{\s*"\w+"\s*:""")
    private val PROMPT_FIELD_REGEX = Regex(
        """"(prompt|template|instruction)"\s*:\s*"((?:[^"\\]|\\.)*)"""",
        RegexOption.IGNORE_CASE,
    )

    fun extractPrompt(raw: String): String? {
        if (raw.isBlank()) return null

        var body = raw

        val fenceMatch = JSON_FENCE_REGEX.find(body)
        if (fenceMatch != null) {
            body = body.substring(0, fenceMatch.range.first)
        }

        val jsonStart = JSON_OBJECT_START_REGEX.find(body)
        if (jsonStart != null) {
            body = body.substring(0, jsonStart.range.first)
        }

        val cleaned = body.trim()
        val candidate =
            if (cleaned.isEmpty()) {
                extractFieldFromJsonObject(raw)?.trim() ?: return null
            } else {
                cleaned
            }

        if (candidate.isEmpty()) return null

        if (raw.contains("{{") && !candidate.contains("{{")) return null

        return candidate
    }

    private fun extractFieldFromJsonObject(raw: String): String? {
        val match = PROMPT_FIELD_REGEX.find(raw) ?: return null
        return unescapeJsonString(match.groupValues[2])
    }

    private fun unescapeJsonString(value: String): String =
        buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '\\' || i == value.lastIndex) {
                    append(c)
                    i++
                    continue
                }
                when (val next = value[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    '"', '\\', '/' -> append(next)
                    'u' -> {
                        if (i + 5 < value.length) {
                            val hex = value.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        append(c).append(next)
                    }
                    else -> append(c).append(next)
                }
                i += 2
            }
        }
}
