package ru.yandex.diploma.aiplatform.application.usecase

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
import ru.yandex.diploma.aiplatform.domain.repository.BaselineRepository
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.ProviderValidationService
import ru.yandex.diploma.aiplatform.domain.service.RegressionDetectionService
import ru.yandex.diploma.aiplatform.infrastructure.service.OptimizationHtmlReportGenerator
import java.time.Instant
import java.util.UUID

@Service
class BaselineExperimentRunner(
    private val runTestSuiteUseCase: RunTestSuiteUseCase,
    private val optimizationExperimentRunner: OptimizationExperimentRunner,
    private val configurationRepository: TestConfigurationRepository,
    private val baselineRepository: BaselineRepository,
    private val regressionDetectionService: RegressionDetectionService,
    private val providerValidationService: ProviderValidationService,
    private val optimizationHtmlReportGenerator: OptimizationHtmlReportGenerator,
) {

    private val logger = LoggerFactory.getLogger(BaselineExperimentRunner::class.java)

    suspend fun executeWithBaseline(
        configurationSource: String,
        executionConfig: ExecutionConfig? = null,
        regressionConfig: RegressionConfiguration? = null,
        baselineModeOverride: BaselinePersistenceMode? = null,
    ): EnhancedTestSuiteResult {
        val configuration = configurationRepository.loadConfiguration(configurationSource)
        val validationErrors = buildList {
            addAll(configuration.validate())
            addAll(providerValidationService.validateConfiguration(configuration))
        }
        if (validationErrors.isNotEmpty()) {
            throw TestSuiteException("Ошибка валидации конфигурации: ${validationErrors.joinToString(", ")}")
        }

        val resolvedRegressionConfig = mergeRegressionConfiguration(
            configuration = configuration,
            regressionOverride = regressionConfig,
            baselineModeOverride = baselineModeOverride
        )

        val suiteId = BaselineKeys.suiteId(configurationSource, configuration.suiteMetadata)

        val resolvedExecutionConfig = executionConfig ?: configuration.executionConfig
        logger.info(
            "Running suite with execution: parallel={}, maxParallelism={}, testTimeout={}",
            resolvedExecutionConfig.enableParallelExecution,
            resolvedExecutionConfig.maxParallelism,
            resolvedExecutionConfig.testTimeout,
        )

        val optimizationConfig = configuration.optimizationConfig
        val suiteExecStarted = System.currentTimeMillis()
        val (optimizationOutcome, originalResult) =
            if (optimizationConfig?.enabled == true) {
                val primaryAgent =
                    configuration.agents.firstOrNull()
                        ?: throw TestSuiteException(
                            "Оптимизация включена (optimizer.enabled), но в конфигурации нет ни одного агента",
                        )
                logger.info(
                    "Optimization enabled: type={}, iterations={}, mode={}",
                    optimizationConfig.type,
                    optimizationConfig.iterations,
                    optimizationConfig.mode,
                )
                val agentForPipeline = AgentConfig.from(primaryAgent, resolvedExecutionConfig)
                val outcome =
                    optimizationExperimentRunner.runExperimentWithOptimization(
                        configurationSource = configurationSource,
                        agentConfig = agentForPipeline,
                        optimizationConfig = optimizationConfig,
                    )
                outcome to suiteResultFromOptimizationOutcome(outcome)
            } else {
                null to runTestSuiteUseCase.execute(configurationSource, resolvedExecutionConfig)
            }

        val suiteExecMs = System.currentTimeMillis() - suiteExecStarted
        if (optimizationOutcome != null) {
            logger.info(
                "baseline_runner optimization_pipeline_done wallClockMs={} optimizationReportedMs={} iterationRounds={}",
                suiteExecMs,
                optimizationOutcome.executionTimeMs,
                optimizationOutcome.iterationSummaries.size,
            )
        } else {
            logger.info(
                "baseline_runner suite_only_done wallClockMs={} suiteReportedMs={}",
                suiteExecMs,
                originalResult.executionTimeMs,
            )
        }

        val testRunRecord = convertToTestRunRecord(
            originalResult = originalResult,
            configurationSource = configurationSource,
            configuration = configuration
        )

        if (resolvedRegressionConfig.baselineMode == BaselinePersistenceMode.RECORD && testRunRecord.hasInfrastructureErrors) {
            logger.warn("Baseline recording skipped due to infrastructure errors")
            throw IllegalStateException(
                "Cannot record baselines: the suite run finished with infrastructure errors " +
                    "(missing LLM provider, timeouts, or execution exceptions). Fix execution before RECORD mode."
            )
        }

        if (resolvedRegressionConfig.baselineMode == BaselinePersistenceMode.RECORD) {
            for (result in testRunRecord.results) {
                val testCaseId = TestCaseIdentifiers.stableTestCaseId(result.testCase)
                baselineRepository.saveBaseline(suiteId, testCaseId, result.toBaselineEntry())
            }
        }

        val storedByTestCaseId =
            when (val load = baselineRepository.loadAll(suiteId)) {
                is BaselineLoadResult.Loaded -> load.data
                is BaselineLoadResult.Missing -> emptyMap()
                is BaselineLoadResult.Corrupted -> {
                    logger.warn(
                        "baseline_store_invalid suiteId={} path={} error={}",
                        suiteId,
                        load.path,
                        load.error,
                    )
                    emptyMap()
                }
            }

        val regressionAnalysis = regressionDetectionService.analyzePersistedBaselines(
            currentRun = testRunRecord,
            baselineMode = resolvedRegressionConfig.baselineMode,
            storedByTestCaseId = storedByTestCaseId,
            regressionRules = resolvedRegressionConfig.rules,
            suiteId = suiteId
        )

        val optimizationReportFile =
            optimizationOutcome?.let { outcome ->
                try {
                    optimizationHtmlReportGenerator.generateOptimizationReport(outcome)
                } catch (e: Exception) {
                    logger.warn("Failed to write optimization HTML report: {}", e.message)
                    null
                }
            }
        val optimizationSummary =
            optimizationOutcome?.let { o ->
                OptimizationRunSummary(
                    iterationRounds = o.iterationSummaries.size,
                    mode = o.config.mode.name,
                    optimizerType = o.config.type.name,
                    executionTimeMs = o.executionTimeMs,
                    optimizationStatus = o.optimizationResult.status.name,
                )
            }

        return EnhancedTestSuiteResult(
            runId = testRunRecord.runId,
            testRun = testRunRecord,
            regressionAnalysis = regressionAnalysis,
            reportFile = originalResult.reportFile,
            optimizationReportFile = optimizationReportFile,
            optimizationSummary = optimizationSummary,
            exitCode = determineExitCode(regressionAnalysis, resolvedRegressionConfig)
        )
    }

    companion object {
        fun mergeRegressionConfiguration(
            configuration: TestConfiguration,
            regressionOverride: RegressionConfiguration?,
            baselineModeOverride: BaselinePersistenceMode?,
        ): RegressionConfiguration {
            val baseRegression =
                regressionOverride ?: (configuration.regressionConfiguration ?: RegressionConfiguration.defaultConfiguration())
            return baseRegression.copy(
                baselineMode = baselineModeOverride ?: baseRegression.baselineMode
            )
        }
    }

    private fun convertToTestRunRecord(
        originalResult: TestSuiteResult,
        configurationSource: String,
        configuration: TestConfiguration
    ): TestRunRecord {
        val enhancedResults = originalResult.results.map { result ->
            convertToTestResultWithMetrics(result)
        }

        val runId = UUID.randomUUID().toString()

        return TestRunRecord(
            runId = runId,
            suiteMetadata = configuration.suiteMetadata,
            promptVersion = configuration.suiteMetadata.version.ifBlank { "1.0" },
            configurationHash = configurationSource.hashCode().toString(),
            results = enhancedResults,
            metrics = originalResult.metrics,
            executionTimeMs = originalResult.executionTimeMs,
            timestamp = Instant.now(),
            tags = emptySet()
        )
    }

    private fun convertToTestResultWithMetrics(result: TestResult): TestResult {
        if (result.metrics.isNotEmpty()) {
            return result
        }

        val additionalMetrics = mutableMapOf<String, MetricResult>()

        additionalMetrics["latency"] = MetricResult(
            name = "latency",
            score = normalizeLatency(result.executionTimeMs),
            explanation = "Normalized latency score based on execution time"
        )

        val tokensUsed = result.llmResponse?.tokensUsed ?: 0
        additionalMetrics["token_usage"] = MetricResult(
            name = "token_usage",
            score = normalizeTokenUsage(tokensUsed),
            explanation = "Normalized token usage score (higher is better)"
        )

        return result.copy(metrics = additionalMetrics)
    }

    private fun normalizeLatency(executionTimeMs: Long): Double {
        val maxLatencyMs = 10000.0
        return (1.0 - (executionTimeMs.toDouble() / maxLatencyMs)).coerceIn(0.0, 1.0)
    }

    private fun normalizeTokenUsage(tokens: Int): Double {
        val maxTokens = 4000.0
        return (1.0 - (tokens.toDouble() / maxTokens)).coerceIn(0.0, 1.0)
    }

    private fun determineExitCode(
        analysis: SuiteRegressionAnalysis,
        config: RegressionConfiguration
    ): Int {
        return when {
            !config.failOnRegression -> 0
            analysis.summary.overallStatus == RegressionStatus.FAILURE -> 1
            else -> 0
        }
    }

    private fun suiteResultFromOptimizationOutcome(outcome: OptimizationExperimentResult): TestSuiteResult {
        val fromOptimized =
            outcome.optimizedExperimentResult?.runs?.firstOrNull { it.result != null }?.result
        val fromBaseline = outcome.baselineResult.runs.firstOrNull { it.result != null }?.result
        return fromOptimized ?: fromBaseline
            ?: throw TestSuiteException(
                "Эксперимент оптимизации завершился без результата тестового набора",
            )
    }
}
