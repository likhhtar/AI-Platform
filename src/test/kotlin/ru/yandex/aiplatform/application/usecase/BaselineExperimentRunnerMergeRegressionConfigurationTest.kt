package ru.yandex.aiplatform.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.application.usecase.BaselineExperimentRunner
import ru.yandex.diploma.aiplatform.domain.model.BaselinePersistenceMode
import ru.yandex.diploma.aiplatform.domain.model.RegressionConfiguration
import ru.yandex.diploma.aiplatform.domain.model.RegressionRule
import ru.yandex.diploma.aiplatform.domain.model.RegressionSeverity
import ru.yandex.diploma.aiplatform.domain.model.RegressionType
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration

class BaselineExperimentRunnerMergeRegressionConfigurationTest {

    private fun minimalConfig(regressionConfiguration: RegressionConfiguration?): TestConfiguration =
        TestConfiguration(
            agents = emptyList(),
            prompts = emptyList(),
            tests = emptyList(),
            regressionConfiguration = regressionConfiguration
        )

    @Test
    fun `falls back to defaultConfiguration when YAML has no regression`() {
        val merged = BaselineExperimentRunner.mergeRegressionConfiguration(
            configuration = minimalConfig(null),
            regressionOverride = null,
            baselineModeOverride = null
        )
        val defaults = RegressionConfiguration.defaultConfiguration()
        assertEquals(defaults.rules.size, merged.rules.size)
        assertEquals(defaults.rules.map { it.metricName }, merged.rules.map { it.metricName })
        assertEquals(BaselinePersistenceMode.ASSERT, merged.baselineMode)
    }

    @Test
    fun `YAML baseline mode preserved when no override`() {
        val fromYaml = RegressionConfiguration.defaultConfiguration().copy(
            baselineMode = BaselinePersistenceMode.RECORD
        )
        val merged = BaselineExperimentRunner.mergeRegressionConfiguration(
            configuration = minimalConfig(fromYaml),
            regressionOverride = null,
            baselineModeOverride = null
        )
        assertEquals(BaselinePersistenceMode.RECORD, merged.baselineMode)
    }

    @Test
    fun `baselineModeOverride replaces YAML baseline mode`() {
        val fromYaml = RegressionConfiguration.defaultConfiguration().copy(
            baselineMode = BaselinePersistenceMode.RECORD
        )
        val merged = BaselineExperimentRunner.mergeRegressionConfiguration(
            configuration = minimalConfig(fromYaml),
            regressionOverride = null,
            baselineModeOverride = BaselinePersistenceMode.ASSERT
        )
        assertEquals(BaselinePersistenceMode.ASSERT, merged.baselineMode)
    }

    @Test
    fun `explicit regression override replaces scenario regression configuration`() {
        val yamlReg = RegressionConfiguration(
            rules = listOf(
                RegressionRule("correctness", 0.01, RegressionType.RELATIVE, RegressionSeverity.ERROR)
            ),
            baselineMode = BaselinePersistenceMode.RECORD
        )
        val override = RegressionConfiguration.defaultConfiguration().copy(
            baselineMode = BaselinePersistenceMode.ASSERT
        )
        val merged = BaselineExperimentRunner.mergeRegressionConfiguration(
            configuration = minimalConfig(yamlReg),
            regressionOverride = override,
            baselineModeOverride = null
        )
        assertEquals(RegressionConfiguration.defaultConfiguration().rules.size, merged.rules.size)
        assertEquals(BaselinePersistenceMode.ASSERT, merged.baselineMode)
    }
}
