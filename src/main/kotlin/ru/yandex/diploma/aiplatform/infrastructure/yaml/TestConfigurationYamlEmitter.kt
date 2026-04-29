package ru.yandex.diploma.aiplatform.infrastructure.yaml

import kotlin.time.Duration
import ru.yandex.diploma.aiplatform.domain.model.AgentConfig
import ru.yandex.diploma.aiplatform.domain.model.ExecutionConfig
import ru.yandex.diploma.aiplatform.domain.model.OptimizationConfig
import ru.yandex.diploma.aiplatform.domain.model.Prompt
import ru.yandex.diploma.aiplatform.domain.model.RegressionConfiguration
import ru.yandex.diploma.aiplatform.domain.model.TestCase
import ru.yandex.diploma.aiplatform.domain.model.TestSuiteMetadata
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration

object TestConfigurationYamlEmitter {

    fun emit(
        configuration: TestConfiguration,
        promptReplacementsById: Map<String, Prompt> = emptyMap(),
        forceOptimizerDisabled: Boolean = false,
        optimizationOverride: OptimizationConfig? = null,
    ): String =
        buildString {
            appendSuite(configuration.suiteMetadata)
            if (configuration.metadata.isNotEmpty()) {
                appendMetadata(configuration.metadata)
            }
            appendExecution(configuration.executionConfig)
            val opt = optimizationOverride
                ?: configuration.optimizationConfig?.let { oc ->
                    if (forceOptimizerDisabled) OptimizationConfig(enabled = false) else oc
                }
            if (opt != null) appendOptimizer(opt)
            if (configuration.regressionConfiguration != null) {
                appendRegression(configuration.regressionConfiguration!!)
            }
            appendLine()
            appendPrompts(configuration.prompts, promptReplacementsById)
            appendLine()
            appendAgents(configuration.agents)
            appendLine()
            appendTests(configuration.tests)
        }.trimEnd() + "\n"

    private fun StringBuilder.appendSuite(meta: TestSuiteMetadata) {
        appendLine("suite:")
        appendLine("  name: ${yamlDoubleQuoted(meta.name)}")
        appendLine("  version: ${yamlDoubleQuoted(meta.version)}")
        if (meta.description.isNotBlank()) appendLine("  description: ${yamlDoubleQuoted(meta.description)}")
        if (meta.author.isNotBlank()) appendLine("  author: ${yamlDoubleQuoted(meta.author)}")
        if (meta.tags.isNotEmpty()) {
            appendLine("  tags:")
            meta.tags.forEach { appendLine("    - ${yamlDoubleQuoted(it)}") }
        }
        if (meta.createdAt.isNotBlank()) appendLine("  createdAt: ${yamlDoubleQuoted(meta.createdAt)}")
        if (meta.updatedAt.isNotBlank()) appendLine("  updatedAt: ${yamlDoubleQuoted(meta.updatedAt)}")
        meta.executionMode?.let { appendLine("  executionMode: ${it.name}") }
        appendLine()
    }

    private fun StringBuilder.appendMetadata(metadata: Map<String, Any>) {
        appendLine("metadata:")
        metadata.entries.sortedBy { it.key }.forEach { (k, v) ->
            appendLine("  $k: ${primitiveToYamlScalar(v)}")
        }
        appendLine()
    }

    private fun StringBuilder.appendExecution(exec: ExecutionConfig) {
        appendLine("execution:")
        appendLine("  maxParallelism: ${exec.maxParallelism}")
        appendLine("  testTimeout: ${yamlDoubleQuoted(exec.testTimeout.asFriendlyDuration())}")
        appendLine("  enableParallelExecution: $exec.enableParallelExecution")
        appendLine()
    }

    private fun StringBuilder.appendOptimizer(config: OptimizationConfig) {
        appendLine("optimizer:")
        appendLine("  enabled: ${config.enabled}")
        appendLine("  mode: ${config.mode.name}")
        appendLine("  type: ${config.type.name}")
        appendLine("  iterations: ${config.iterations}")
        appendLine()
    }

    private fun StringBuilder.appendRegression(reg: RegressionConfiguration) {
        appendLine("regression:")
        appendLine("  failOnRegression: ${reg.failOnRegression}")
        appendLine("  baselineStrategy: ${reg.baselineStrategy.name}")
        appendLine("  baselineMode: ${reg.baselineMode.name}")
        if (reg.enabledMetrics.isNotEmpty()) {
            appendLine("  enabledMetrics:")
            reg.enabledMetrics.sorted().forEach { appendLine("    - ${yamlDoubleQuoted(it)}") }
        }
        appendLine("  rules:")
        for (rule in reg.rules) {
            appendLine("    - metricName: ${yamlDoubleQuoted(rule.metricName)}")
            appendLine("      threshold: ${rule.threshold}")
            appendLine("      type: ${rule.type.name}")
            appendLine("      severity: ${rule.severity.name}")
            if (rule.description.isNotBlank()) {
                appendLine("      description: ${yamlDoubleQuoted(rule.description)}")
            }
            appendLine("      direction: ${rule.direction.name}")
        }
        appendLine()
    }

    private fun StringBuilder.appendPrompts(
        prompts: List<Prompt>,
        replacements: Map<String, Prompt>,
    ) {
        appendLine("prompts:")
        prompts.forEach { p ->
            val effective = replacements[p.id] ?: p
            appendLine("  - id: ${yamlDoubleQuoted(effective.id)}")
            appendLine("    name: ${yamlDoubleQuoted(effective.name)}")
            append("    template: ")
            appendTemplateYamlValue(effective.template)
        }
    }

    private fun StringBuilder.appendTemplateYamlValue(template: String) {
        val normalized = template.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.length < 320 && !normalized.contains('\n') && !looksLikeMultilineYamlTrap(normalized)) {
            appendLine(yamlDoubleQuoted(normalized))
        } else {
            appendLine("|")
            val lines = normalized.split("\n")
            for (raw in lines) {
                appendLine("      ${raw.replace("\t", "  ")}")
            }
        }
    }

    private fun looksLikeMultilineYamlTrap(s: String): Boolean =
        s.count { it == '"' || it == '\\' } > 12 || s.length > 260

    private fun StringBuilder.appendAgents(agents: List<AgentConfig>) {
        appendLine("agents:")
        agents.forEach { agent ->
            appendLine("  - name: ${yamlDoubleQuoted(agent.name)}")
            appendLine("    provider: ${yamlDoubleQuoted(agent.provider)}")
            appendLine("    systemPrompt: ${yamlDoubleQuoted(agent.systemPrompt)}")
            agent.model?.let { appendLine("    model: ${yamlDoubleQuoted(it)}") }
            appendLine("    temperature: ${agent.temperature}")
            agent.maxTokens?.let { appendLine("    maxTokens: $it") }
            agent.topP?.let { appendLine("    topP: $it") }
            agent.frequencyPenalty?.let { appendLine("    frequencyPenalty: $it") }
            agent.presencePenalty?.let { appendLine("    presencePenalty: $it") }
            if (agent.additionalParameters.isNotEmpty()) {
                appendLine("    additionalParameters:")
                agent.additionalParameters.forEach { (k, v) ->
                    appendLine("      $k: ${primitiveToYamlScalar(v)}")
                }
            }
        }
    }

    private fun StringBuilder.appendTests(tests: List<TestCase>) {
        appendLine("tests:")
        tests.forEach { tc ->
            appendLine("  - promptId: ${yamlDoubleQuoted(tc.promptId)}")
            if (tc.agentName != null) {
                appendLine("    agent: ${yamlDoubleQuoted(tc.agentName!!)}")
            } else {
                appendLine("    agents:")
                tc.agentNames.forEach { appendLine("      - ${yamlDoubleQuoted(it)}") }
            }
            appendLine("    variables:")
            tc.variables.keys.sorted().forEach { key ->
                val value = tc.variables[key]!!
                appendLine("      $key: ${scalarYaml(value)}")
            }
            appendLine("    expected: ${yamlDoubleQuoted(tc.expected)}")
            appendLine("    evaluator: ${yamlDoubleQuoted(tc.evaluatorType)}")
            if (tc.metadata.isNotEmpty()) {
                appendLine("    metadata:")
                tc.metadata.forEach { (k, v) ->
                    appendLine("      $k: ${primitiveToYamlScalar(v)}")
                }
            }
        }
    }

    private fun Duration.asFriendlyDuration(): String =
        when {
            inWholeSeconds % 60L == 0L && inWholeMinutes > 0 && inWholeSeconds == inWholeMinutes * 60L ->
                "${inWholeMinutes}m"
            else -> "${inWholeSeconds}s"
        }

    private fun yamlDoubleQuoted(value: String): String =
        '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + '"'

    private fun scalarYaml(s: String): String =
        if (Regex("""[:#\-?[\]{},]|\s""").containsMatchIn(s)) yamlDoubleQuoted(s) else s

    private fun primitiveToYamlScalar(v: Any): String =
        when (v) {
            is String -> yamlDoubleQuoted(v)
            is Boolean -> v.toString()
            is Number -> v.toString()
            else -> yamlDoubleQuoted(v.toString())
        }
}
