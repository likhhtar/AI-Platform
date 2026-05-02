package ru.yandex.diploma.aiplatform.infrastructure.service

import kotlin.math.abs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentRunner
import ru.yandex.diploma.aiplatform.application.usecase.safePassRate
import ru.yandex.diploma.aiplatform.application.usecase.safeTestPassRate
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.model.TestCaseIdentifiers
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationService
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizerRegistry
import ru.yandex.diploma.aiplatform.infrastructure.yaml.TestConfigurationYamlEmitter

@Service
class DefaultPromptOptimizationService(
    private val optimizerRegistry: PromptOptimizerRegistry,
    private val experimentRunner: ExperimentRunner,
    private val testConfigurationRepository: TestConfigurationRepository,
) : PromptOptimizationService {

    private val logger = LoggerFactory.getLogger(DefaultPromptOptimizationService::class.java)

    override suspend fun runOptimizationExperiment(
        configurationSource: String,
        agentConfig: AgentConfig,
        optimizationConfig: OptimizationConfig,
    ): OptimizationExperimentResult {
        val startTime = System.currentTimeMillis()

        try {
            logger.info("Starting optimization pipeline with optimizer {}", optimizationConfig.type)

            if (!optimizationConfig.enabled) {
                throw PromptOptimizationException("Optimization is disabled in configuration")
            }

            val baselineResult =
                experimentRunner.runExperiment(configurationSource, agentConfig)

            if (!baselineResult.runs.any { it.success }) {
                throw PromptOptimizationException("Baseline experiment failed, cannot optimize")
            }

            return when {
                optimizationConfig.iterations <= 1 ->
                    optimizeFromExperimentResult(
                        baselineResult,
                        optimizationConfig,
                        configurationSource = configurationSource,
                    ).copy(executionTimeMs = System.currentTimeMillis() - startTime)

                else ->
                    optimizeIterativelyFromBaseline(
                        initialBaselineResult = baselineResult,
                        originalConfigurationSource = configurationSource,
                        optimizationConfig,
                    ).copy(
                        executionTimeMs = System.currentTimeMillis() - startTime,
                    )
            }
        } catch (e: PromptOptimizationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Optimization experiment failed", e)
            throw PromptOptimizationException(
                message = "Optimization experiment failed: ${e.message}",
                cause = e,
            )
        }
    }

    override suspend fun optimizeFromExperimentResult(
        experimentResult: ExperimentResult,
        optimizationConfig: OptimizationConfig,
        configurationSource: String?,
    ): OptimizationExperimentResult {
        val started = System.currentTimeMillis()
        return try {
            val round =
                runSingleOptimizationStep(
                    baselineForImprovementMetrics = experimentResult,
                    optimizationConfig = optimizationConfig,
                    configurationSource = configurationSource,
                    iterationRound = null,
                )

            OptimizationExperimentResult(
                baselineResult = experimentResult,
                optimizationResult = round.optimizationResult,
                optimizedExperimentResult = round.optimizedExperimentResult,
                improvement = round.improvement,
                config = optimizationConfig,
                executionTimeMs = System.currentTimeMillis() - started,
                iterationSummaries = emptyList(),
            )
        } catch (e: PromptOptimizationException) {
            throw e
        } catch (e: Exception) {
            logger.error("optimizeFromExperimentResult failed", e)
            throw PromptOptimizationException(
                message = "Optimization failed: ${e.message}",
                cause = e,
            )
        }
    }

    private suspend fun optimizeIterativelyFromBaseline(
        initialBaselineResult: ExperimentResult,
        originalConfigurationSource: String,
        optimizationConfig: OptimizationConfig,
    ): OptimizationExperimentResult {
        val started = System.currentTimeMillis()
        var yamlSource = originalConfigurationSource
        var chainBaseline = initialBaselineResult
        val summaries = mutableListOf<OptimizationIterationSummary>()
        var lastOptimizationResult: OptimizationResult? = null
        var lastImprovement: OptimizationImprovement? = null
        var lastOptimizedExperiment: ExperimentResult? = null
        val configurationFingerprints = mutableSetOf<String>()

        for (round in 1..optimizationConfig.iterations) {
            val loadedForFingerprint =
                runCatching { testConfigurationRepository.loadConfiguration(yamlSource) }
                    .getOrElse { ex ->
                        throw PromptOptimizationException(
                            "Cannot compute semantic fingerprint, failed loading configuration: ${ex.message}",
                            ex,
                        )
                    }
            val fingerprint = loadedForFingerprint.semanticFingerprint()
            if (fingerprint in configurationFingerprints) {
                logOptimizationAudit(
                    OptimizationAuditEvent(
                        iteration = round,
                        optimizerType = optimizationConfig.type,
                        status = OptimizationStatus.SUGGESTED,
                        scoreDelta = lastImprovement?.scoreImprovement,
                        medianDelta = lastImprovement?.medianScoreImprovement,
                        passRateDelta = lastImprovement?.passRateImprovement,
                        cycleDetected = true,
                        plateauDetected = false,
                        rollbackReason = null,
                    ),
                )
                logger.info(
                    "optimization_cycle_detected iteration={} optimizerType={} semanticFingerprintPrefix={}",
                    round,
                    optimizationConfig.type,
                    fingerprint.take(16),
                )
                summaries +=
                    OptimizationIterationSummary(
                        round = round,
                        improvement = null,
                        haltedDueToPlateau = false,
                        haltedDueToCycle = true,
                    )
                break
            }
            configurationFingerprints.add(fingerprint)

            logger.info("Optimization iteration {} / {}", round, optimizationConfig.iterations)

            val step =
                runSingleOptimizationStep(
                    baselineForImprovementMetrics = chainBaseline,
                    optimizationConfig = optimizationConfig,
                    configurationSource = yamlSource,
                    iterationRound = round,
                )
            lastOptimizationResult = step.optimizationResult
            lastImprovement = step.improvement
            lastOptimizedExperiment = step.optimizedExperimentResult

            val haltedPlateau =
                round >= 2 &&
                    lastImprovement != null &&
                    !lastImprovement.regressionDetected &&
                    abs(lastImprovement.medianScoreImprovement) < optimizationConfig.plateauScoreEpsilon

            summaries +=
                OptimizationIterationSummary(
                    round = round,
                    improvement = lastImprovement,
                    haltedDueToPlateau = haltedPlateau,
                    haltedDueToCycle = false,
                )

            if (haltedPlateau) {
                logOptimizationAudit(
                    OptimizationAuditEvent(
                        iteration = round,
                        optimizerType = optimizationConfig.type,
                        status = lastOptimizationResult?.status ?: OptimizationStatus.SUGGESTED,
                        scoreDelta = lastImprovement?.scoreImprovement,
                        medianDelta = lastImprovement?.medianScoreImprovement,
                        passRateDelta = lastImprovement?.passRateImprovement,
                        cycleDetected = false,
                        plateauDetected = true,
                        rollbackReason = null,
                    ),
                )
                logger.info(
                    "Stopping iterative optimization, median delta {} within plateau epsilon {}",
                    lastImprovement!!.medianScoreImprovement,
                    optimizationConfig.plateauScoreEpsilon,
                )
                break
            }

            if (lastImprovement?.rolledBack == true) {
                logOptimizationAudit(
                    OptimizationAuditEvent(
                        iteration = round,
                        optimizerType = optimizationConfig.type,
                        status = OptimizationStatus.ROLLED_BACK,
                        scoreDelta = lastImprovement!!.scoreImprovement,
                        medianDelta = lastImprovement.medianScoreImprovement,
                        passRateDelta = lastImprovement.passRateImprovement,
                        cycleDetected = false,
                        plateauDetected = false,
                        rollbackReason = lastImprovement.rollbackReason,
                    ),
                )
                logger.info(
                    "Stopping iterative optimization, optimized run discarded (regression vs chain baseline) rollbackReason={}",
                    lastImprovement!!.rollbackReason ?: "",
                )
                break
            }

            if (optimizationConfig.mode != OptimizationMode.APPLY) {
                logger.debug("Stopping, further iterations need APPLY mode to materialize prompts in YAML.")
                break
            }

            if (step.optimizationResult.optimizedPrompt == null || step.optimizedExperimentResult == null) {
                break
            }

            if (round >= optimizationConfig.iterations) {
                break
            }

            try {
                val modifiedYaml =
                    createModifiedConfiguration(
                        experimentResult = chainBaseline,
                        optimizedPrompt = step.optimizationResult.optimizedPrompt!!,
                        configurationSourceFallback = yamlSource,
                    )
                yamlSource = modifiedYaml
                chainBaseline = step.optimizedExperimentResult!!
            } catch (e: Exception) {
                logger.warn("Failed emitting next configuration for iteration chain", e)
                break
            }
        }

        checkNotNull(lastOptimizationResult) {
            "Iterative optimization produced no optimizer output"
        }

        return OptimizationExperimentResult(
            baselineResult = initialBaselineResult,
            optimizationResult = lastOptimizationResult,
            optimizedExperimentResult = lastOptimizedExperiment,
            improvement = lastImprovement,
            config = optimizationConfig,
            iterationSummaries = summaries,
            executionTimeMs = System.currentTimeMillis() - started,
        )
    }

    private data class OptimizationRoundArtifacts(
        val optimizationResult: OptimizationResult,
        val optimizedExperimentResult: ExperimentResult?,
        val improvement: OptimizationImprovement?,
    )

    private suspend fun runSingleOptimizationStep(
        baselineForImprovementMetrics: ExperimentResult,
        optimizationConfig: OptimizationConfig,
        configurationSource: String?,
        iterationRound: Int?,
    ): OptimizationRoundArtifacts {
        val optimizer =
            optimizerRegistry.getOptimizer(optimizationConfig.type)
                ?: throw PromptOptimizationException(
                    "Optimizer ${optimizationConfig.type} not available",
                )

        if (!optimizer.isAvailable()) {
            throw PromptOptimizationException(
                "Optimizer ${optimizationConfig.type} is not available",
            )
        }

        val optimizationInput =
            extractOptimizationInput(baselineForImprovementMetrics, configurationSource)

        val optimizationResult = optimizer.optimize(optimizationInput, optimizationConfig)

        var optimizedExperimentResult: ExperimentResult? = null
        var improvement: OptimizationImprovement? = null

        if (
            optimizationConfig.mode == OptimizationMode.APPLY &&
            optimizationResult.optimizedPrompt != null
        ) {
            logger.info(
                "Re-running harness with optimized prompt `${optimizationResult.optimizedPrompt!!.id}`",
            )
            try {
                val modifiedYaml =
                    createModifiedConfiguration(
                        experimentResult = baselineForImprovementMetrics,
                        optimizedPrompt = optimizationResult.optimizedPrompt!!,
                        configurationSourceFallback = configurationSource,
                    )

                optimizedExperimentResult =
                    experimentRunner.runExperiment(
                        configurationSource = modifiedYaml,
                        agentConfig = baselineForImprovementMetrics.agentConfig,
                    )

                improvement =
                    calculateImprovement(
                        baseline = baselineForImprovementMetrics,
                        optimized = optimizedExperimentResult!!,
                        optimizationConfig = optimizationConfig,
                    )

                val mergedMeta = optimizationResult.metadata.toMutableMap()
                improvement.rollbackReason?.let { mergedMeta["rollbackReason"] = it }

                val finalOptimizedPrompt =
                    if (improvement.rolledBack) null else optimizationResult.optimizedPrompt

                val derivedStatus =
                    deriveOptimizationStatus(
                        mode = optimizationConfig.mode,
                        optimizedPrompt = finalOptimizedPrompt,
                        improvement = improvement,
                        harnessEvaluationFailed = false,
                    )
                mergedMeta["optimizationStatus"] = derivedStatus.name

                logOptimizationAudit(
                    OptimizationAuditEvent(
                        iteration = iterationRound,
                        optimizerType = optimizationConfig.type,
                        status = derivedStatus,
                        scoreDelta = improvement.scoreImprovement,
                        medianDelta = improvement.medianScoreImprovement,
                        passRateDelta = improvement.passRateImprovement,
                        cycleDetected = false,
                        plateauDetected = false,
                        rollbackReason = improvement.rollbackReason,
                    ),
                )

                val tagged =
                    optimizationResult.copy(
                        optimizedPrompt = finalOptimizedPrompt,
                        status = derivedStatus,
                        metadata = mergedMeta,
                    )

                return OptimizationRoundArtifacts(
                    optimizationResult = tagged,
                    optimizedExperimentResult = optimizedExperimentResult,
                    improvement = improvement,
                )
            } catch (e: Exception) {
                logger.warn("Failed to evaluate optimized prompt in harness", e)
                val failedMeta = optimizationResult.metadata.toMutableMap()
                failedMeta["optimizationStatus"] = OptimizationStatus.FAILED.name
                failedMeta["rollbackReason"] = "harness_evaluation_failed: ${e.message}"
                return OptimizationRoundArtifacts(
                    optimizationResult =
                        optimizationResult.copy(
                            optimizedPrompt = null,
                            status = OptimizationStatus.FAILED,
                            metadata = failedMeta,
                        ),
                    optimizedExperimentResult = null,
                    improvement = null,
                )
            }
        }

        val metaOut = optimizationResult.metadata.toMutableMap()
        metaOut["optimizationStatus"] = optimizationResult.status.name
        return OptimizationRoundArtifacts(
            optimizationResult = optimizationResult.copy(metadata = metaOut),
            optimizedExperimentResult = optimizedExperimentResult,
            improvement = improvement,
        )
    }

    private suspend fun extractOptimizationInput(
        experimentResult: ExperimentResult,
        configurationSourceFallback: String?,
    ): OptimizationInput {
        val source =
            configurationSourceFallback
                ?: experimentResult.configurationSource
                ?: throw PromptOptimizationException(
                    "Missing configuration YAML/source, cannot resolve real prompt templates. " +
                        "Pass configurationSource explicitly or ensure ExperimentRunner attaches it.",
                )

        val successfulRun =
            experimentResult.runs.firstOrNull { it.success && it.result != null }
                ?: throw PromptOptimizationException(
                    "No successful experiment runs (empty or failing suite).",
                )

        val suiteResults = successfulRun.result!!.results
        if (suiteResults.isEmpty()) {
            throw PromptOptimizationException(
                "Baseline produced no per-test records, optimizer needs concrete scores per test.",
            )
        }

        val configuration =
            runCatching { testConfigurationRepository.loadConfiguration(source) }
                .getOrElse { ex ->
                    throw PromptOptimizationException(
                        "Failed loading configuration `$source`: ${ex.message}",
                        ex,
                    )
                }

        val focus =
            suiteResults.minByOrNull { it.evaluationResult.score }
                ?: suiteResults.first()
        val prompt =
            configuration.getPrompt(focus.testCase.promptId)
                ?: throw PromptOptimizationException(
                    """
                    Prompt id `${focus.testCase.promptId}` not found in loaded configuration.
                    Ensure tests reference existing prompts.
                    """.trimIndent(),
                )

        return OptimizationInput(
            originalPrompt = prompt,
            testCases = suiteResults.map { it.testCase },
            testResults = suiteResults,
            agentConfig = experimentResult.agentConfig,
        )
    }

    private suspend fun createModifiedConfiguration(
        experimentResult: ExperimentResult,
        optimizedPrompt: Prompt,
        configurationSourceFallback: String?,
    ): String {
        val source =
            configurationSourceFallback
                ?: experimentResult.configurationSource
                ?: throw PromptOptimizationException(
                    "Cannot serialize optimized suite, missing YAML source.",
                )

        val base =
            testConfigurationRepository.loadConfiguration(source)

        val mergedOptimized =
            optimizedPrompt.copy(
                id =
                    optimizedPrompt.id.ifBlank {
                        throw PromptOptimizationException("Optimized prompt blank id")
                    },
            )

        return TestConfigurationYamlEmitter.emit(
            configuration = base,
            promptReplacementsById =
                mapOf(
                    mergedOptimized.id to mergedOptimized,
                ),
            forceOptimizerDisabled = true,
            optimizationOverride =
                OptimizationConfig(
                    enabled = false,
                    mode = OptimizationMode.SUGGEST,
                    type = OptimizerType.LLM,
                ),
        )
    }

    private fun calculateImprovement(
        baseline: ExperimentResult,
        optimized: ExperimentResult,
        optimizationConfig: OptimizationConfig,
    ): OptimizationImprovement {
        val baseMetrics = baseline.metrics
        val optMetrics = optimized.metrics

        val scoreImprovement =
            optMetrics.averageScore - baseMetrics.averageScore

        val baseScores = baseline.scoreSamples()
        val optScores = optimized.scoreSamples()
        val commonKeys = baseScores.keys intersect optScores.keys

        val pairedDeltas =
            commonKeys.map { key ->
                val b = baseScores.getValue(key)
                val o = optScores.getValue(key)
                key to (o - b)
            }

        val medianImprovement =
            when {
                pairedDeltas.isEmpty() ->
                    scoreImprovement

                else ->
                    median(pairedDeltas.map { it.second })
            }

        val latencyChange =
            optMetrics.averageLatency - baseMetrics.averageLatency

        val basePassRate = baseline.safeTestPassRate()
        val optimizedPassRate = optimized.safeTestPassRate()
        val passRateImprovement = optimizedPassRate - basePassRate

        val t = optimizationConfig.rollbackMedianThreshold.coerceAtLeast(MIN_SCORE_DELTA_DETECT)

        val medianRegression = medianImprovement < -t
        val distributedRegression =
            pairedDeltas.size >= MIN_PAIRED_FOR_DISTRIBUTED_CHECK &&
                pairedDeltas.count { (_, d) -> d <= -t } >
                pairedDeltas.size * FRACTION_REGRESSED_LABEL

        val meanRegressionWithoutPairs =
            pairedDeltas.isEmpty() && scoreImprovement < -t

        val regressionDetected =
            medianRegression ||
                distributedRegression ||
                meanRegressionWithoutPairs ||
                passRateImprovement < -PASS_RATE_TOLERANCE

        val significantImprovement =
            commonKeys.isNotEmpty() &&
                !regressionDetected &&
                (
                    medianImprovement >= 0.04 ||
                        scoreImprovement >= 0.06 ||
                        passRateImprovement >= 0.1 ||
                        (
                            pairedDeltas.count { (_, d) -> d >= 0.05 } >=
                                (commonKeys.size * 0.25).coerceAtLeast(1.0).toInt()
                            )
                    )

        val rolledBack =
            regressionDetected ||
                medianImprovement <= -ROLLBACK_HARD_FLOOR ||
                scoreImprovement <= -ROLLBACK_HARD_FLOOR

        val rollbackParts = mutableListOf<String>()
        if (medianImprovement < -t) rollbackParts += "median score delta ${"%.4f".format(medianImprovement)} < -threshold $t"
        if (passRateImprovement < -PASS_RATE_TOLERANCE) {
            rollbackParts += "pass rate delta ${"%.4f".format(passRateImprovement)} below tolerance"
        }
        if (meanRegressionWithoutPairs) rollbackParts += "aggregate score regression without matched test ids"

        return OptimizationImprovement(
            scoreImprovement = scoreImprovement,
            latencyChange = latencyChange,
            passRateImprovement = passRateImprovement,
            significantImprovement = significantImprovement,
            regressionDetected = regressionDetected,
            rolledBack = rolledBack,
            rollbackReason = rollbackParts.takeIf { it.isNotEmpty() && rolledBack }?.joinToString("; "),
            detailedMetrics =
                buildMap {
                    put("baselineAverageScore", baseMetrics.averageScore)
                    put("optimizedAverageScore", optMetrics.averageScore)
                    put("baselineMedianScore", median(baseScores.values.toList()))
                    put("optimizedMedianScore", median(optScores.values.toList()))
                    put("baselineLatency", baseMetrics.averageLatency)
                    put("optimizedLatency", optMetrics.averageLatency)
                    put("baselinePassRate", basePassRate)
                    put("optimizedPassRate", optimizedPassRate)
                    put("baselineSuccessfulRunRate", baseline.safePassRate())
                    put("optimizedSuccessfulRunRate", optimized.safePassRate())
                    put("matchedTests", commonKeys.size.toDouble())
                    put(
                        "countSignificantRegressionTests",
                        pairedDeltas.count { (_, d) -> d <= -t }.toDouble(),
                    )
                },
            medianScoreImprovement = medianImprovement,
            perTestScoreDelta = pairedDeltas.toMap(),
        )
    }

    private fun ExperimentResult.scoreSamples(): Map<String, Double> {
        val suite =
            runs.firstOrNull { it.success && it.result != null }?.result
                ?: return emptyMap()
        return suite.results.associate { tr ->
            TestCaseIdentifiers.stableTestCaseId(tr.testCase) to tr.evaluationResult.score
        }
    }

    private fun logOptimizationAudit(event: OptimizationAuditEvent) {
        logger.info(
            "optimization_audit iteration={} optimizerType={} status={} scoreDelta={} medianDelta={} passRateDelta={} cycleDetected={} plateauDetected={} rollbackReason={}",
            event.iteration ?: -1,
            event.optimizerType.name,
            event.status.name,
            event.scoreDelta?.toString() ?: "n/a",
            event.medianDelta?.toString() ?: "n/a",
            event.passRateDelta?.toString() ?: "n/a",
            event.cycleDetected,
            event.plateauDetected,
            event.rollbackReason ?: "",
        )
    }

    private fun median(values: List<Double>): Double =
        when (values.size) {
            0 -> 0.0
            1 -> values.first()

            else -> {
                val sorted = values.sorted()
                val mid = sorted.size / 2
                if (sorted.size % 2 == 1) {
                    sorted[mid]
                } else {
                    (sorted[mid - 1] + sorted[mid]) / 2.0
                }
            }
        }

    companion object {
        private const val MIN_SCORE_DELTA_DETECT = 0.005
        private const val PASS_RATE_TOLERANCE = 0.06
        private const val ROLLBACK_HARD_FLOOR = 0.01
        private const val MIN_PAIRED_FOR_DISTRIBUTED_CHECK = 3
        private const val FRACTION_REGRESSED_LABEL = 0.35
    }
}
