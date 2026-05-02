package ru.yandex.diploma.aiplatform.domain.model

import java.io.File
import java.security.MessageDigest

object BaselineKeys {
    fun suiteId(configurationSource: String, suiteMetadata: TestSuiteMetadata): String {
        val name = suiteMetadata.name.trim()
        if (name.isNotBlank() && name != "Unnamed Test Suite") {
            return sanitizeForStorage(name)
        }
        if (isLikelyFilePath(configurationSource)) {
            return sanitizeForStorage(File(configurationSource).nameWithoutExtension.ifBlank { "suite" })
        }
        return "suite-" + sha256Short(configurationSource)
    }

    // `suiteId` + `::` + `stableTestCaseId` (suiteId never contains `::`).
    fun logicalBaselineKey(suiteId: String, testCaseId: String): String {
        require("::" !in suiteId) { "suiteId must not contain '::'" }
        return "$suiteId::$testCaseId"
    }

    fun parseLogicalBaselineKey(logicalKey: String): Pair<String, String> {
        val idx = logicalKey.indexOf("::")
        require(idx > 0 && idx < logicalKey.length - 2) { "Invalid logical baseline key" }
        return logicalKey.substring(0, idx) to logicalKey.substring(idx + 2)
    }

    fun isLikelyFilePath(source: String): Boolean {
        val s = source.trim()
        return '\n' !in s && !s.startsWith("{") && !s.startsWith("-")
    }

    fun sanitizeForStorage(raw: String): String {
        return raw
            .lowercase()
            .map { c ->
                when {
                    c.isLetterOrDigit() -> c
                    c == '_' || c == '-' -> c
                    else -> '_'
                }
            }
            .joinToString("")
            .trim('_')
            .take(120)
            .ifBlank { "suite" }
    }

    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
