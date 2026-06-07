package ru.yandex.aiplatform.infrastructure.yaml

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import ru.yandex.diploma.aiplatform.domain.repository.ConfigurationLoadException
import ru.yandex.diploma.aiplatform.infrastructure.yaml.YamlTestConfigurationRepository

class YamlOptimizerStrictEnumTest {

    private val repo = YamlTestConfigurationRepository()

    @Test
    fun `invalid optimizer mode throws ConfigurationLoadException with expected values`() = runBlocking {
        val yaml =
            """
            suite:
              name: "t"
              version: "1"
            prompts:
              - id: p1
                name: p
                template: "x"
            agents:
              - name: ag
                systemPrompt: s
                provider: deterministic
                model: deterministic-model-v1
            tests:
              - promptId: p1
                agent: ag
                variables: {}
                expected: "x"
                evaluator: exact
            optimizer:
              enabled: true
              mode: APPLYY
              type: LLM
              llm:
                provider: openai
                model: gpt-4
            """.trimIndent()

        val ex = assertFailsWith<ConfigurationLoadException> { repo.loadConfiguration(yaml) }
        assertTrue(ex.message!!.contains("optimizer.mode", ignoreCase = true))
        assertTrue(ex.message!!.contains("APPLYY"))
        assertTrue(ex.message!!.contains("SUGGEST") || ex.message!!.contains("APPLY"))
    }

    @Test
    fun `invalid optimizer type throws ConfigurationLoadException`() = runBlocking {
        val yaml =
            """
            suite:
              name: "t"
              version: "1"
            prompts:
              - id: p1
                name: p
                template: "x"
            agents:
              - name: ag
                systemPrompt: s
                provider: deterministic
                model: deterministic-model-v1
            tests:
              - promptId: p1
                agent: ag
                variables: {}
                expected: "x"
                evaluator: exact
            optimizer:
              enabled: true
              mode: SUGGEST
              type: LLM_X
              llm:
                provider: openai
                model: gpt-4
            """.trimIndent()

        val ex = assertFailsWith<ConfigurationLoadException> { repo.loadConfiguration(yaml) }
        assertTrue(ex.message!!.contains("optimizer.type", ignoreCase = true))
        assertTrue(ex.message!!.contains("LLM_X"))
    }

    @Test
    fun `optimizer parses use_lineage and use_lamarckian snake_case`() = runBlocking {
        val yaml =
            """
            suite:
              name: "t"
              version: "1"
            prompts:
              - id: p1
                name: p
                template: "x"
            agents:
              - name: ag
                systemPrompt: s
                provider: deterministic
                model: deterministic-model-v1
            tests:
              - promptId: p1
                agent: ag
                variables: {}
                expected: "x"
                evaluator: exact
            optimizer:
              enabled: true
              mode: SUGGEST
              type: LLM
              use_lineage: false
              use_lamarckian: false
              llm:
                provider: openai
                model: gpt-4
            """.trimIndent()

        val oc = assertNotNull(repo.loadConfiguration(yaml).optimizationConfig)
        assertFalse(oc.useLineage)
        assertFalse(oc.useLamarckian)
    }

    @Test
    fun `optimizer parses useLineage and useLamarckian camelCase`() = runBlocking {
        val yaml =
            """
            suite:
              name: "t"
              version: "1"
            prompts:
              - id: p1
                name: p
                template: "x"
            agents:
              - name: ag
                systemPrompt: s
                provider: deterministic
                model: deterministic-model-v1
            tests:
              - promptId: p1
                agent: ag
                variables: {}
                expected: "x"
                evaluator: exact
            optimizer:
              enabled: true
              mode: SUGGEST
              type: LLM
              useLineage: false
              useLamarckian: false
              llm:
                provider: openai
                model: gpt-4
            """.trimIndent()

        val oc = assertNotNull(repo.loadConfiguration(yaml).optimizationConfig)
        assertFalse(oc.useLineage)
        assertFalse(oc.useLamarckian)
    }
}
