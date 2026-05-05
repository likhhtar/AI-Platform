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

fun validateTemplatePlaceholderBagPreservation(originalTemplate: String, candidateTemplate: String) {
    val expected = extractTemplateVariableBag(originalTemplate)
    val actual = extractTemplateVariableBag(candidateTemplate)
    if (expected == actual) return
    val missing = expected.keys.associateWith { (expected[it] ?: 0) - (actual[it] ?: 0) }.filterValues { it > 0 }
    val extra = actual.keys.associateWith { (actual[it] ?: 0) - (expected[it] ?: 0) }.filterValues { it > 0 }
    throw IllegalArgumentException(
        "Template placeholder multiset mismatch.\nExpected: $expected\nGot: $actual\n" +
            "Missing counts: $missing, extra counts: $extra",
    )
}

fun replaceRegexOutsidePlaceholderSpans(template: String, regex: Regex, literalReplacement: String): String {
    if (!TEMPLATE_PLACEHOLDER_SPAN_PATTERN.containsMatchIn(template)) {
        return regex.replace(template, literalReplacement)
    }
    val out = StringBuilder()
    var idx = 0
    for (match in TEMPLATE_PLACEHOLDER_SPAN_PATTERN.findAll(template)) {
        if (match.range.first > idx) {
            val plain = template.substring(idx, match.range.first)
            out.append(regex.replace(plain, literalReplacement))
        }
        out.append(match.value)
        idx = match.range.last + 1
    }
    if (idx < template.length) {
        out.append(regex.replace(template.substring(idx), literalReplacement))
    }
    return out.toString()
}
