package ru.yandex.diploma.aiplatform.domain.model

private val TEMPLATE_VARIABLE_PATTERN =
    Regex("""(?<!\{)\{\{(?!\{)\s*([A-Za-z0-9_.-]+)\s*}}(?!\})""")

private val TEMPLATE_PLACEHOLDER_SPAN_PATTERN =
    Regex("""(?<!\{)\{\{(?!\{)\s*[A-Za-z0-9_.-]+\s*}}(?!\})""")

fun extractTemplateVariables(template: String): Set<String> =
    extractTemplateVariableBag(template).keys

fun extractTemplateVariableBag(template: String): Map<String, Int> {
    val bag = mutableMapOf<String, Int>()
    for (m in TEMPLATE_VARIABLE_PATTERN.findAll(template)) {
        val name = m.groupValues[1]
        bag[name] = (bag[name] ?: 0) + 1
    }
    return bag
}

fun missingTemplatePlaceholders(originalTemplate: String, candidateTemplate: String): List<String> {
    val expected = extractTemplateVariableBag(originalTemplate)
    val actual = extractTemplateVariableBag(candidateTemplate)
    return expected.keys
        .filter { name -> (actual[name] ?: 0) < (expected[name] ?: 0) }
        .sorted()
}

fun validateTemplatePlaceholderBagPreservation(originalTemplate: String, candidateTemplate: String) {
    val expected = extractTemplateVariableBag(originalTemplate)
    val actual = extractTemplateVariableBag(candidateTemplate)
    if (expected == actual) return
    val missingCounts =
        expected.keys.associateWith { (expected[it] ?: 0) - (actual[it] ?: 0) }.filterValues { it > 0 }
    val extra = actual.keys.associateWith { (actual[it] ?: 0) - (expected[it] ?: 0) }.filterValues { it > 0 }
    throw IllegalArgumentException(
        "Template placeholder multiset mismatch.\nExpected: $expected\nGot: $actual\n" +
            "Missing counts: $missingCounts, extra counts: $extra",
    )
}

fun transformOutsidePlaceholderSpans(template: String, transform: (String) -> String): String {
    if (!TEMPLATE_PLACEHOLDER_SPAN_PATTERN.containsMatchIn(template)) {
        return transform(template)
    }
    val out = StringBuilder()
    var idx = 0
    for (match in TEMPLATE_PLACEHOLDER_SPAN_PATTERN.findAll(template)) {
        if (match.range.first > idx) {
            out.append(transform(template.substring(idx, match.range.first)))
        }
        out.append(match.value)
        idx = match.range.last + 1
    }
    if (idx < template.length) {
        out.append(transform(template.substring(idx)))
    }
    return out.toString()
}

fun containsMatchOutsidePlaceholderSpans(template: String, regex: Regex): Boolean {
    if (!TEMPLATE_PLACEHOLDER_SPAN_PATTERN.containsMatchIn(template)) {
        return regex.containsMatchIn(template)
    }
    var idx = 0
    for (match in TEMPLATE_PLACEHOLDER_SPAN_PATTERN.findAll(template)) {
        if (match.range.first > idx) {
            val plain = template.substring(idx, match.range.first)
            if (regex.containsMatchIn(plain)) return true
        }
        idx = match.range.last + 1
    }
    if (idx < template.length) {
        return regex.containsMatchIn(template.substring(idx))
    }
    return false
}

fun replaceRegexOutsidePlaceholderSpans(template: String, regex: Regex, literalReplacement: String): String =
    transformOutsidePlaceholderSpans(template) { plain ->
        regex.replace(plain, literalReplacement)
    }
