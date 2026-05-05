package ru.yandex.aiplatform.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.yandex.diploma.aiplatform.domain.model.extractTemplateVariableBag
import ru.yandex.diploma.aiplatform.domain.model.replaceRegexOutsidePlaceholderSpans
import ru.yandex.diploma.aiplatform.domain.model.validateTemplatePlaceholderBagPreservation

class PromptTemplateBagTest {

    @Test
    fun `bag counts duplicate placeholders separately`() {
        assertEquals(mapOf("x" to 2), extractTemplateVariableBag("{{x}} {{ x }}"))
        assertEquals(mapOf("x" to 1), extractTemplateVariableBag("{{x}}"))
        assertEquals(mapOf("user.name" to 1), extractTemplateVariableBag("Hello {{ user.name }}!"))
    }

    @Test
    fun `validate rejects multiset mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            validateTemplatePlaceholderBagPreservation("{{a}} {{a}}", "{{a}}")
        }
    }

    @Test
    fun `regex replace skips placeholder spans`() {
        val pattern = Regex("foo", RegexOption.IGNORE_CASE)
        val tpl = "foo {{ bar }} foo"
        val out = replaceRegexOutsidePlaceholderSpans(tpl, pattern, "baz")
        assertEquals("baz {{ bar }} baz", out)
    }

    @Test
    fun `triple brace does not produce placeholder`() {
        assertEquals(emptyMap(), extractTemplateVariableBag("{{{x}}}"))
    }

    @Test
    fun `hyphenated and dotted identifiers`() {
        assertEquals(mapOf("user-id" to 1, "user.name" to 1), extractTemplateVariableBag("{{ user-id }} {{user.name}}"))
    }

    @Test
    fun `bracket expression not matched as placeholder`() {
        assertEquals(emptyMap(), extractTemplateVariableBag("""{{ user["id"] }}"""))
    }
}
