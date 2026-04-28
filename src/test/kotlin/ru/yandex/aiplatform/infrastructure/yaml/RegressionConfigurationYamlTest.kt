package ru.yandex.aiplatform.infrastructure.yaml

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.BaselinePersistenceMode
import ru.yandex.diploma.aiplatform.domain.model.BaselineStrategy
import ru.yandex.diploma.aiplatform.domain.model.RegressionSeverity
import ru.yandex.diploma.aiplatform.domain.model.RegressionType
import ru.yandex.diploma.aiplatform.infrastructure.yaml.YamlTestConfigurationRepository

class RegressionConfigurationYamlTest {

    private val repository = YamlTestConfigurationRepository()

    private val yamlBase = """
        suite:
          name: my-suite

        prompts:
          - id: p1
            template: "Hello {{name}}"

        agents:
          - name: a1
            provider: mock
            model: m
            system_prompt: ""

        tests:
          - promptId: p1
            agent: a1
            variables:
              name: X
            expected: "yes"
    """.trimIndent()

    @Test
    fun `parses regression section with rules and options`() = runBlocking {
        val yaml = yamlBase + """

            regression:
              failOnRegression: true
              baselineStrategy: ACTIVE
              baselineMode: ASSERT
              enabledMetrics:
                - correctness
                - latency
              rules:
                - metricName: correctness
                  threshold: 0.03
                  type: RELATIVE
                  severity: ERROR

                - metricName: latency
                  threshold: 0.4
                  type: RELATIVE
                  severity: WARNING
        """.trimIndent()

        val config = repository.loadConfiguration(yaml.trim())
        val reg = config.regressionConfiguration!!
        assertEquals(true, reg.failOnRegression)
        assertEquals(BaselineStrategy.ACTIVE, reg.baselineStrategy)
        assertEquals(BaselinePersistenceMode.ASSERT, reg.baselineMode)
        assertEquals(setOf("correctness", "latency"), reg.enabledMetrics)
        assertEquals(2, reg.rules.size)

        val cRule = reg.rules.find { it.metricName == "correctness" }!!
        assertEquals(0.03, cRule.threshold)
        assertEquals(RegressionType.RELATIVE, cRule.type)
        assertEquals(RegressionSeverity.ERROR, cRule.severity)

        val lRule = reg.rules.find { it.metricName == "latency" }!!
        assertEquals(0.4, lRule.threshold)
        assertEquals(RegressionSeverity.WARNING, lRule.severity)
    }

    @Test
    fun `regression section absent yields null regressionConfiguration`() = runBlocking {
        val config = repository.loadConfiguration(yamlBase.trim())
        assertNull(config.regressionConfiguration)
    }

    @Test
    fun `empty rules list falls back to default rules`() = runBlocking {
        val yaml = yamlBase + """

            regression:
              baselineMode: RECORD
              failOnRegression: false
              rules: []
        """.trimIndent()

        val reg = repository.loadConfiguration(yaml.trim()).regressionConfiguration!!
        assertEquals(BaselinePersistenceMode.RECORD, reg.baselineMode)
        assertEquals(false, reg.failOnRegression)
        assertTrue(reg.rules.isNotEmpty())
    }
}
