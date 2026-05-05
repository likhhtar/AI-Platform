package ru.yandex.diploma.aiplatform.infrastructure.yaml

import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.repository.ConfigurationLoadException
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import org.springframework.stereotype.Repository
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.StringReader
import java.util.regex.Pattern

@Repository
class YamlTestConfigurationRepository : TestConfigurationRepository {
    
    private val yaml = Yaml()
    private val variablePattern =
        Pattern.compile("(?<!\\{)\\{\\{(?!\\{)\\s*([A-Za-z0-9_.-]+)\\s*}}(?!\\})")
    
    override suspend fun loadConfiguration(source: String): TestConfiguration {
        try {
            val yamlContent = if (isFilePath(source)) {
                File(source).readText()
            } else {
                source
            }
            
            val data = yaml.load<Map<String, Any>>(StringReader(yamlContent))
                ?: throw ConfigurationLoadException("Empty or invalid YAML", source = source)
            
            return parseConfiguration(data, source)
        } catch (e: ConfigurationLoadException) {
            throw e
        } catch (e: Exception) {
            throw ConfigurationLoadException(
                message = "Failed to load configuration: ${e.message}",
                cause = e,
                source = source
            )
        }
    }
    
    override suspend fun validateConfiguration(source: String): List<String> {
        return try {
            val configuration = loadConfiguration(source)
            configuration.validate()
        } catch (e: ConfigurationLoadException) {
            listOf("Configuration load error: ${e.message}")
        } catch (e: Exception) {
            listOf("Validation error: ${e.message}")
        }
    }
    
    private fun parseConfiguration(data: Map<String, Any>, source: String): TestConfiguration {
        try {
            val agents = parseAgents(data["agents"].asListOfStringKeyedMapsOrEmpty())
            val prompts = parsePrompts(data["prompts"].asListOfStringKeyedMapsOrEmpty())
            
            val testsData = data["tests"].asListOfStringKeyedMapsOrEmpty()
                .ifEmpty { data["test_cases"].asListOfStringKeyedMapsOrEmpty() }
            
            val tests = parseTests(testsData)
            
            if (tests.isEmpty()) {
                throw ConfigurationLoadException(
                    message = "No tests found in configuration. Expected 'tests' or 'test_cases' key with at least one test case.",
                    source = source
                )
            }
            
            val metadata = data["metadata"].asStringKeyedMapOrEmpty()
            
            val suiteMetadata = parseSuiteMetadata(data["suite"].asStringKeyedMapOrEmpty())
            val executionConfig = parseExecutionConfig(data["execution"].asStringKeyedMapOrEmpty())
            val optimizationConfig =
                parseOptimizationConfig(data["optimizer"].asStringKeyedMapOrEmpty(), source = source)
            val regressionConfiguration = parseRegressionConfiguration(data["regression"])

            return TestConfiguration(
                agents = agents,
                prompts = prompts,
                tests = tests,
                metadata = metadata,
                suiteMetadata = suiteMetadata,
                executionConfig = executionConfig,
                optimizationConfig = optimizationConfig,
                regressionConfiguration = regressionConfiguration
            )
        } catch (e: ConfigurationLoadException) {
            throw e
        } catch (e: Exception) {
            throw ConfigurationLoadException(
                message = "Failed to parse configuration: ${e.message}",
                cause = e,
                source = source
            )
        }
    }
    
    private fun parseAgents(agentData: List<Map<String, Any>>): List<AgentConfig> {
        return agentData.map { data ->
            AgentConfig(
                name = data["name"] as? String ?: throw IllegalArgumentException("Agent name is required"),
                provider = data["provider"] as? String ?: throw IllegalArgumentException("Agent provider is required"),
                systemPrompt = (data["systemPrompt"] as? String) ?: (data["system_prompt"] as? String) ?: "",
                model = data["model"] as? String,
                temperature = (data["temperature"] as? Number)?.toDouble() ?: 0.7,
                maxTokens = (data["maxTokens"] as? Number)?.toInt(),
                topP = (data["topP"] as? Number)?.toDouble(),
                frequencyPenalty = (data["frequencyPenalty"] as? Number)?.toDouble(),
                presencePenalty = (data["presencePenalty"] as? Number)?.toDouble(),
                additionalParameters = data["additionalParameters"].asStringKeyedMapOrEmpty()
            )
        }
    }
    
    private fun parsePrompts(promptData: List<Map<String, Any>>): List<Prompt> {
        return promptData.map { data ->
            val template = data["template"] as? String ?: throw IllegalArgumentException("Prompt template is required")
            val variables = extractVariables(template)
            
            Prompt(
                id = data["id"] as? String ?: throw IllegalArgumentException("Prompt id is required"),
                name = data["name"] as? String ?: (data["id"] as String),
                template = template,
                variables = variables
            )
        }
    }
    
    private fun parseTests(testData: List<Map<String, Any>>): List<TestCase> {
        return testData.map { data ->
            val agentName = data["agent"] as? String
            val agentNames = data["agents"].asStringListOrEmpty()
            
            val promptId = (data["promptId"] as? String) ?: (data["prompt_id"] as? String)
                ?: throw IllegalArgumentException("Test promptId (or prompt_id) is required")
            
            TestCase(
                promptId = promptId,
                agentName = agentName,
                agentNames = agentNames,
                variables = data["variables"].asStringKeyedMapOrEmpty().mapValues { it.value.toString() },
                expected = data["expected"] as? String ?: throw IllegalArgumentException("Test expected result is required"),
                evaluatorType = data["evaluator"] as? String ?: "exact",
                metadata = data["metadata"].asStringKeyedMapOrEmpty()
            )
        }
    }
    
    private fun extractVariables(template: String): Set<String> {
        val variables = mutableSetOf<String>()
        val matcher = variablePattern.matcher(template)
        while (matcher.find()) {
            variables.add(matcher.group(1))
        }
        return variables
    }
    
    private fun parseSuiteMetadata(data: Map<String, Any>): TestSuiteMetadata {
        val executionModeRaw = (data["executionMode"] ?: data["execution_mode"]) as? String
        val executionMode = executionModeRaw?.let { raw ->
            try {
                ExecutionMode.valueOf(raw.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid suite.executionMode: '$raw' (expected DETERMINISTIC or REAL)"
                )
            }
        }
        return TestSuiteMetadata(
            name = data["name"] as? String ?: "Unnamed Test Suite",
            version = data["version"] as? String ?: "1.0",
            description = data["description"] as? String ?: "",
            author = data["author"] as? String ?: "",
            tags = data["tags"].asStringListOrEmpty(),
            createdAt = data["createdAt"] as? String ?: "",
            updatedAt = data["updatedAt"] as? String ?: "",
            executionMode = executionMode
        )
    }
    
    private fun parseExecutionConfig(data: Map<String, Any>): ExecutionConfig {
        return ExecutionConfig(
            maxParallelism = (data["maxParallelism"] as? Number)?.toInt() ?: 4,
            testTimeout = kotlin.time.Duration.parse(data["testTimeout"] as? String ?: "5m"),
            enableParallelExecution = data["enableParallelExecution"] as? Boolean ?: true
        )
    }
    
    private fun parseRegressionConfiguration(regressionRaw: Any?): RegressionConfiguration? {
        val map = regressionRaw?.asStringKeyedMapOrEmpty() ?: return null
        if (map.isEmpty()) return null
        val base = RegressionConfiguration.defaultConfiguration()
        val failOnRegression = map["failOnRegression"] as? Boolean
            ?: map["fail_on_regression"] as? Boolean
            ?: base.failOnRegression
        val baselineStrategyRaw = (map["baselineStrategy"] ?: map["baseline_strategy"]) as? String
        val baselineStrategy = baselineStrategyRaw?.trim()?.uppercase()?.let { raw ->
            try {
                BaselineStrategy.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid regression.baselineStrategy: '$baselineStrategyRaw' (expected ACTIVE, LATEST, or TAGGED)"
                )
            }
        } ?: base.baselineStrategy
        val baselineModeRaw = (map["baselineMode"] ?: map["baseline_mode"]) as? String
        val baselineMode = baselineModeRaw?.trim()?.uppercase()?.let { raw ->
            try {
                BaselinePersistenceMode.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid regression.baselineMode: '$baselineModeRaw' (expected RECORD or ASSERT)"
                )
            }
        } ?: base.baselineMode
        val enabledMetricsRaw = map["enabledMetrics"] ?: map["enabled_metrics"]
        val enabledMetrics = if (enabledMetricsRaw != null) {
            enabledMetricsRaw.asStringListOrEmpty().toSet()
        } else {
            base.enabledMetrics
        }
        val rulesData = map["rules"].asListOfStringKeyedMapsOrEmpty()
        val rules = if (rulesData.isEmpty()) base.rules else rulesData.map { parseRegressionRule(it) }

        return RegressionConfiguration(
            rules = rules,
            enabledMetrics = enabledMetrics,
            failOnRegression = failOnRegression,
            baselineStrategy = baselineStrategy,
            baselineMode = baselineMode
        )
    }

    private fun parseRegressionRule(data: Map<String, Any>): RegressionRule {
        val metricName =
            data["metricName"] as? String ?: data["metric_name"] as? String
            ?: throw IllegalArgumentException("regression.rules entry requires metricName")
        val threshold =
            (data["threshold"] as? Number)?.toDouble()
                ?: throw IllegalArgumentException("regression.rules entry for '$metricName' requires threshold")
        val typeStr = data["type"] as? String ?: RegressionType.RELATIVE.name
        val type = try {
            RegressionType.valueOf(typeStr.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid regression rule type '$typeStr' for metric '$metricName'")
        }
        val severityStr = data["severity"] as? String ?: RegressionSeverity.WARNING.name
        val severity = try {
            RegressionSeverity.valueOf(severityStr.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid regression severity '$severityStr' for metric '$metricName'")
        }
        val description = data["description"] as? String ?: ""
        val directionRaw = data["direction"] as? String
        val direction = directionRaw?.trim()?.uppercase()?.let { raw ->
            try {
                MetricDirection.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid regression rule direction '$directionRaw' for metric '$metricName'"
                )
            }
        } ?: MetricDirections.forMetric(metricName)
        return RegressionRule(
            metricName = metricName,
            threshold = threshold,
            type = type,
            severity = severity,
            description = description,
            direction = direction
        )
    }

    private fun parseOptimizationConfig(data: Map<String, Any>, source: String): OptimizationConfig? {
        if (data.isEmpty()) return null
        
        val enabled = data["enabled"] as? Boolean ?: false
        if (!enabled) return OptimizationConfig(enabled = false)
        
        val modeStr = data["mode"] as? String
        val mode =
            if (modeStr.isNullOrBlank()) {
                OptimizationMode.SUGGEST
            } else {
                parseOptimizerMode(modeStr, source)
            }
        
        val typeStr = data["type"] as? String
        val type =
            if (typeStr.isNullOrBlank()) {
                OptimizerType.LLM
            } else {
                parseOptimizerType(typeStr, source)
            }
        
        val iterations = (data["iterations"] as? Number)?.toInt() ?: 1
        val plateauScoreEpsilon =
            (data["plateauScoreEpsilon"] as? Number)?.toDouble()
                ?: (data["plateau_score_epsilon"] as? Number)?.toDouble()
                ?: 0.008
        val rollbackMedianThreshold =
            (data["rollbackMedianThreshold"] as? Number)?.toDouble()
                ?: (data["rollback_median_threshold"] as? Number)?.toDouble()
                ?: 0.015

        val llmConfig = if (type == OptimizerType.LLM) {
            parseLlmOptimizerConfig(data["llm"].asStringKeyedMapOrEmpty())
        } else null
        
        val ruleBasedConfig = if (type == OptimizerType.RULE_BASED) {
            parseRuleBasedOptimizerConfig(data["rules"].asStringKeyedMapOrEmpty())
        } else null
        
        return OptimizationConfig(
            enabled = enabled,
            mode = mode,
            type = type,
            iterations = iterations,
            llmConfig = llmConfig,
            ruleBasedConfig = ruleBasedConfig,
            plateauScoreEpsilon = plateauScoreEpsilon.coerceAtLeast(0.0),
            rollbackMedianThreshold = rollbackMedianThreshold.coerceAtLeast(0.0),
        )
    }
    
    private fun parseLlmOptimizerConfig(data: Map<String, Any>): LlmOptimizerConfig {
        return LlmOptimizerConfig(
            provider = data["provider"] as? String ?: "openai",
            model = data["model"] as? String ?: "gpt-3.5-turbo",
            temperature = (data["temperature"] as? Number)?.toDouble() ?: 0.3,
            maxTokens = (data["maxTokens"] as? Number)?.toInt(),
            systemPrompt = data["systemPrompt"] as? String
        )
    }
    
    private fun parseRuleBasedOptimizerConfig(data: Map<String, Any>): RuleBasedOptimizerConfig {
        val rulesData = data["customRules"].asListOfStringKeyedMapsOrEmpty()
        val rules = rulesData.map { ruleData ->
            OptimizationRule(
                name = ruleData["name"] as? String ?: "unnamed",
                description = ruleData["description"] as? String ?: "",
                pattern = ruleData["pattern"] as? String ?: "",
                replacement = ruleData["replacement"] as? String ?: "",
                enabled = ruleData["enabled"] as? Boolean ?: true
            )
        }
        
        return RuleBasedOptimizerConfig(
            rules = rules,
            enableLengthOptimization = data["enableLengthOptimization"] as? Boolean ?: true,
            enableClarityOptimization = data["enableClarityOptimization"] as? Boolean ?: true,
            enableSpecificityOptimization = data["enableSpecificityOptimization"] as? Boolean ?: true
        )
    }
    
    private fun parseOptimizerMode(raw: String, source: String): OptimizationMode {
        val normalized = raw.trim().uppercase()
        return OptimizationMode.entries.firstOrNull { it.name == normalized }
            ?: throw ConfigurationLoadException(
                message =
                    "Invalid optimizer.mode='$raw'. " +
                        "Expected: ${OptimizationMode.entries.joinToString(",") { it.name }} (yaml path: optimizer.mode)",
                source = source,
            )
    }

    private fun parseOptimizerType(raw: String, source: String): OptimizerType {
        val normalized = raw.trim().uppercase().replace("-", "_")
        return OptimizerType.entries.firstOrNull { it.name == normalized }
            ?: throw ConfigurationLoadException(
                message =
                    "Invalid optimizer.type='$raw'. " +
                        "Expected: ${OptimizerType.entries.joinToString(",") { it.name }} (yaml path: optimizer.type)",
                source = source,
            )
    }

    private fun isFilePath(source: String): Boolean {
        return !source.contains('\n') && !source.trim().startsWith("{") && !source.trim().startsWith("-")
    }

    private fun Any?.asStringKeyedMapOrEmpty(): Map<String, Any> {
        if (this !is Map<*, *>) return emptyMap()
        return this.entries.associate { (k, v) ->
            (k as? String ?: k.toString()) to v as Any
        }
    }

    private fun Any?.asListOfStringKeyedMapsOrEmpty(): List<Map<String, Any>> {
        if (this !is List<*>) return emptyList()
        return this.mapNotNull { elem ->
            if (elem is Map<*, *>) {
                elem.entries.associate { (k, v) -> (k as? String ?: k.toString()) to v as Any }
            } else {
                null
            }
        }
    }

    private fun Any?.asStringListOrEmpty(): List<String> {
        if (this !is List<*>) return emptyList()
        return this.mapNotNull { it as? String }
    }
}