package ru.yandex.diploma.aiplatform.infrastructure.service

import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.application.optimization.EdaCandidateGenerator
import ru.yandex.diploma.aiplatform.application.optimization.LamarckianCandidateGenerator
import ru.yandex.diploma.aiplatform.application.optimization.LineageAwareOptimizationPromptBuilder
import ru.yandex.diploma.aiplatform.application.optimization.MutationPromptEvolver
import ru.yandex.diploma.aiplatform.application.optimization.TextGradCandidateGenerator
import ru.yandex.diploma.aiplatform.application.optimization.ZeroOrderFallbackGenerator
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentResult
import ru.yandex.diploma.aiplatform.application.usecase.ExperimentRunner
import ru.yandex.diploma.aiplatform.application.usecase.safePassRate
import ru.yandex.diploma.aiplatform.application.usecase.safeTestPassRate
import ru.yandex.diploma.aiplatform.application.usecase.toBaselineEntry
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.model.TestCaseIdentifiers
import ru.yandex.diploma.aiplatform.domain.model.missingTemplatePlaceholders
import ru.yandex.diploma.aiplatform.domain.repository.BaselineLoadResult
import ru.yandex.diploma.aiplatform.domain.repository.BaselineRepository
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import ru.yandex.diploma.aiplatform.domain.repository.TestConfigurationRepository
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationException
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizationService
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizer
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizerRegistry
import ru.yandex.diploma.aiplatform.infrastructure.yaml.TestConfigurationYamlEmitter

@Service
class DefaultPromptOptimizationService(
    private val optimizerRegistry: PromptOptimizerRegistry,
    private val experimentRunner: ExperimentRunner,
    private val testConfigurationRepository: TestConfigurationRepository,
    private val baselineRepository: BaselineRepository,
    private val lineageAwareOptimizationPromptBuilder: LineageAwareOptimizationPromptBuilder,
    private val lamarckianCandidateGenerator: LamarckianCandidateGenerator,
    private val mutationPromptEvolver: MutationPromptEvolver,
    private val zeroOrderFallbackGenerator: ZeroOrderFallbackGenerator,
    private val edaCandidateGenerator: EdaCandidateGenerator,
    private val textGradCandidateGenerator: TextGradCandidateGenerator,
) : PromptOptimizationService {

    private val logger = LoggerFactory.getLogger(DefaultPromptOptimizationService::class.java)

    private var currentMutationPrompt: String = ""
    private var successfulIterations: Int = 0
    private var totalIterations: Int = 0

    private sealed class IterationCandidateMode {
        data object Standard : IterationCandidateMode()

        data class ZeroOrderFallback(val instruction: String) : IterationCandidateMode()
    }

    private fun resetOptimizationIterationState(optimizationConfig: OptimizationConfig) {
        currentMutationPrompt = optimizationConfig.mutationPrompt
        successfulIterations = 0
        totalIterations = 0
    }

    override suspend fun runOptimizationExperiment(
        configurationSource: String,
        agentConfig: AgentConfig,
        optimizationConfig: OptimizationConfig,
    ): OptimizationExperimentResult {
        val startTime = System.currentTimeMillis()

        try {
            logger.info(
                "Starting optimization pipeline optimizer={} iterations={} plateauEpsilon={}",
                optimizationConfig.type,
                optimizationConfig.iterations,
                optimizationConfig.plateauScoreEpsilon,
            )

            if (!optimizationConfig.enabled) {
                throw PromptOptimizationException("Optimization is disabled in configuration")
            }

            val baselineStarted = System.currentTimeMillis()
            val baselineResult =
                experimentRunner.runExperiment(configurationSource, agentConfig)
            logger.info(
                "optimization_phase baseline_suite_done durationMs={} experimentWallMs={} suiteReportedMs={} testsPassed={}/{}",
                System.currentTimeMillis() - baselineStarted,
                baselineResult.executionTimeMs,
                baselineResult.runs.firstOrNull()?.result?.executionTimeMs ?: -1L,
                baselineResult.runs.firstOrNull()?.result?.passed ?: -1,
                baselineResult.runs.firstOrNull()?.result?.total ?: -1,
            )

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
            resetOptimizationIterationState(optimizationConfig)
            val round =
                runSingleOptimizationStep(
                    baselineForImprovementMetrics = experimentResult,
                    optimizationConfig = optimizationConfig,
                    configurationSource = configurationSource,
                    iterationRound = 1,
                    iterationCandidateMode = IterationCandidateMode.Standard,
                )

            OptimizationExperimentResult(
                baselineResult = experimentResult,
                optimizationResult = round.optimizationResult,
                optimizedExperimentResult = round.optimizedExperimentResult,
                improvement = round.improvement,
                config = optimizationConfig,
                executionTimeMs = System.currentTimeMillis() - started,
                iterationSummaries = emptyList(),
                iterationReportRows =
                    listOf(
                        buildIterationReportRow(
                            iteration = 1,
                            baselineBeforeStep = experimentResult,
                            artifacts = round,
                        ),
                    ),
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
        resetOptimizationIterationState(optimizationConfig)
        var yamlSource = originalConfigurationSource
        var chainBaseline = initialBaselineResult
        val summaries = mutableListOf<OptimizationIterationSummary>()
        val iterationReportRows = mutableListOf<OptimizationIterationReportRow>()
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
            var fingerprintAddedThisRound = false
            val iterationCandidateMode: IterationCandidateMode =
                if (fingerprint in configurationFingerprints) {
                    if (!optimizationConfig.evolveMutationPrompt) {
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
                        iterationReportRows +=
                            cycleStoppedIterationReportRow(round, chainBaseline)
                        break
                    }
                    val problemDescription =
                        buildProblemDescription(chainBaseline, loadedForFingerprint)
                    val fallbackInstruction =
                        zeroOrderFallbackGenerator.generate(problemDescription)
                    if (fallbackInstruction == null) {
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
                            "optimization_cycle_fallback_failed iteration={} semanticFingerprintPrefix={}",
                            round,
                            fingerprint.take(16),
                        )
                        summaries +=
                            OptimizationIterationSummary(
                                round = round,
                                improvement = null,
                                haltedDueToPlateau = false,
                                haltedDueToCycle = true,
                            )
                        iterationReportRows +=
                            cycleStoppedIterationReportRow(round, chainBaseline)
                        break
                    }
                    logger.info(
                        "optimization_cycle_zero_order_fallback iteration={} semanticFingerprintPrefix={}",
                        round,
                        fingerprint.take(16),
                    )
                    IterationCandidateMode.ZeroOrderFallback(fallbackInstruction)
                } else {
                    configurationFingerprints.add(fingerprint)
                    fingerprintAddedThisRound = true
                    IterationCandidateMode.Standard
                }

            logger.info("Optimization iteration {} / {}", round, optimizationConfig.iterations)

            val stepStarted = System.currentTimeMillis()
            val step =
                runSingleOptimizationStep(
                    baselineForImprovementMetrics = chainBaseline,
                    optimizationConfig = optimizationConfig,
                    configurationSource = yamlSource,
                    iterationRound = round,
                    iterationCandidateMode = iterationCandidateMode,
                )
            logger.info(
                "optimization_iteration_step_done round={}/{} stepDurationMs={} medianDelta={} scoreDelta={} rolledBack={} optimizedPromptGenerated={}",
                round,
                optimizationConfig.iterations,
                System.currentTimeMillis() - stepStarted,
                step.improvement?.medianScoreImprovement,
                step.improvement?.scoreImprovement,
                step.improvement?.rolledBack == true,
                step.optimizationResult.optimizedPrompt != null,
            )
            lastOptimizationResult = step.optimizationResult
            lastImprovement = step.improvement
            lastOptimizedExperiment = step.optimizedExperimentResult

            iterationReportRows +=
                buildIterationReportRow(
                    iteration = round,
                    baselineBeforeStep = chainBaseline,
                    artifacts = step,
                )

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
                if (fingerprintAddedThisRound) {
                    configurationFingerprints.remove(fingerprint)
                }
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
                    "optimization_iteration_rolled_back continuing iteration={}/{} rollbackReason={}",
                    round,
                    optimizationConfig.iterations,
                    lastImprovement.rollbackReason ?: "",
                )
                continue
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

        val iterativeTotalMs = System.currentTimeMillis() - started
        logger.info(
            "optimization_iterative_complete totalDurationMs={} summariesCount={} lastMedianDelta={}",
            iterativeTotalMs,
            summaries.size,
            lastImprovement?.medianScoreImprovement,
        )

        return OptimizationExperimentResult(
            baselineResult = initialBaselineResult,
            optimizationResult = lastOptimizationResult,
            optimizedExperimentResult = lastOptimizedExperiment,
            improvement = lastImprovement,
            config = optimizationConfig,
            iterationSummaries = summaries,
            iterationReportRows = iterationReportRows,
            executionTimeMs = iterativeTotalMs,
        )
    }

    private data class OptimizationRoundArtifacts(
        val optimizationResult: OptimizationResult,
        val optimizedExperimentResult: ExperimentResult?,
        val improvement: OptimizationImprovement?,
        val proposedPromptTemplateSnapshot: String? = null,
    )

    private suspend fun runSingleOptimizationStep(
        baselineForImprovementMetrics: ExperimentResult,
        optimizationConfig: OptimizationConfig,
        configurationSource: String?,
        iterationRound: Int?,
        iterationCandidateMode: IterationCandidateMode,
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

        val optimizationInputBase =
            extractOptimizationInput(baselineForImprovementMetrics, configurationSource)

        val source =
            configurationSource
                ?: baselineForImprovementMetrics.configurationSource
                ?: throw PromptOptimizationException(
                    "Missing configuration YAML/source, cannot resolve real prompt templates. " +
                        "Pass configurationSource explicitly or ensure ExperimentRunner attaches it.",
                )

        val loadedConfiguration = testConfigurationRepository.loadConfiguration(source)
        val suiteId = BaselineKeys.suiteId(source, loadedConfiguration.suiteMetadata)

        val lineageVersions =
            if (!optimizationConfig.useLineage && !optimizationConfig.useEda) {
                emptyList()
            } else {
                when (val loaded = baselineRepository.loadAll(suiteId)) {
                    is BaselineLoadResult.Loaded ->
                        loaded.data.values.mapNotNull { it.promptVersion }
                    else -> emptyList()
                }
            }

        val builtMetaPrompt =
            lineageAwareOptimizationPromptBuilder.buildPrompt(
                currentPrompt = optimizationInputBase.originalPrompt.template,
                mutationPrompt = currentMutationPrompt,
                versions = lineageVersions,
            )

        val inputWithMeta =
            optimizationInputBase.copy(metaPromptOverride = builtMetaPrompt)

        return when (iterationCandidateMode) {
            is IterationCandidateMode.ZeroOrderFallback ->
                runZeroOrderFallbackOptimizationStep(
                    baselineForImprovementMetrics = baselineForImprovementMetrics,
                    optimizationConfig = optimizationConfig,
                    configurationSource = configurationSource,
                    iterationRound = iterationRound,
                    optimizationInputBase = optimizationInputBase,
                    instruction = iterationCandidateMode.instruction,
                )

            IterationCandidateMode.Standard ->
                runStandardOptimizationStep(
                    baselineForImprovementMetrics = baselineForImprovementMetrics,
                    optimizationConfig = optimizationConfig,
                    configurationSource = configurationSource,
                    iterationRound = iterationRound,
                    optimizer = optimizer,
                    optimizationInputWithMeta = inputWithMeta,
                    optimizationInputBase = optimizationInputBase,
                    lineageVersions = lineageVersions,
                    loadedConfiguration = loadedConfiguration,
                )
        }
    }

    private suspend fun runZeroOrderFallbackOptimizationStep(
        baselineForImprovementMetrics: ExperimentResult,
        optimizationConfig: OptimizationConfig,
        configurationSource: String?,
        iterationRound: Int?,
        optimizationInputBase: OptimizationInput,
        instruction: String,
    ): OptimizationRoundArtifacts {
        if (
            !isCandidateTemplatePlaceholderValid(
                candidateLabel = "zero_order_fallback",
                originalTemplate = optimizationInputBase.originalPrompt.template,
                candidateTemplate = instruction,
            )
        ) {
            throw PromptOptimizationException(
                "Zero-order fallback candidate rejected: template placeholders not preserved",
            )
        }
        val optimizedPrompt =
            Prompt(
                id = optimizationInputBase.originalPrompt.id,
                name = optimizationInputBase.originalPrompt.name,
                template = instruction,
                variables = optimizationInputBase.originalPrompt.variables,
            )
        val optimizationResult =
            OptimizationResult(
                originalPrompt = optimizationInputBase.originalPrompt,
                optimizedPrompt = optimizedPrompt,
                suggestions = emptyList(),
                confidence = 0.65,
                reasoning = "Zero-order fallback candidate (semantic cycle)",
                metadata =
                    mapOf(
                        "candidateSource" to "zero_order_fallback",
                    ),
                executionTimeMs = 0L,
                status =
                    if (optimizationConfig.mode == OptimizationMode.APPLY) {
                        OptimizationStatus.APPLIED
                    } else {
                        OptimizationStatus.SUGGESTED
                    },
            )
        return applyHarnessEvaluationAndPostProcess(
            baselineForImprovementMetrics = baselineForImprovementMetrics,
            optimizationConfig = optimizationConfig,
            configurationSource = configurationSource,
            iterationRound = iterationRound,
            optimizationResult = optimizationResult,
        )
    }

    private suspend fun runStandardOptimizationStep(
        baselineForImprovementMetrics: ExperimentResult,
        optimizationConfig: OptimizationConfig,
        configurationSource: String?,
        iterationRound: Int?,
        optimizer: PromptOptimizer,
        optimizationInputWithMeta: OptimizationInput,
        optimizationInputBase: OptimizationInput,
        lineageVersions: List<PromptVersion>,
        loadedConfiguration: TestConfiguration,
    ): OptimizationRoundArtifacts =
        coroutineScope {
            val deferredPrimary =
                async {
                    runCatching {
                        optimizer.optimize(optimizationInputWithMeta, optimizationConfig)
                    }
                }
            val judgeExplanations = extractJudgeExplanations(baselineForImprovementMetrics)
            val deferredLamarckian =
                if (optimizationConfig.useLamarckian) {
                    async {
                        lamarckianCandidateGenerator.generate(judgeExplanations)
                    }
                } else {
                    null
                }
            val deferredEda =
                if (optimizationConfig.useEda) {
                    async {
                        edaCandidateGenerator.generate(lineageVersions)
                    }
                } else {
                    null
                }
            val deferredTextGrad =
                if (optimizationConfig.useTextGrad) {
                    async {
                        textGradCandidateGenerator.generate(
                            originalPrompt = optimizationInputBase.originalPrompt.template,
                            judgeExplanations = judgeExplanations,
                        )
                    }
                } else {
                    null
                }

            val primaryResult = deferredPrimary.await()
            val lamarckianTemplate = deferredLamarckian?.await()
            val edaTemplate = deferredEda?.await()
            val textGradTemplate = deferredTextGrad?.await()

            val auxiliaryCandidates: List<Pair<String, String>> =
                buildList {
                    lamarckianTemplate?.let { add("lamarckian" to it) }
                    edaTemplate?.let { add("eda" to it) }
                    textGradTemplate?.let { add("textgrad" to it) }
                }

            if (optimizationConfig.mode != OptimizationMode.APPLY) {
                val originalTemplate = optimizationInputBase.originalPrompt.template
                val chosen =
                    primaryResult.getOrNull()
                        ?: auxiliaryCandidates.firstNotNullOfOrNull { (label, template) ->
                            if (
                                !isCandidateTemplatePlaceholderValid(
                                    candidateLabel = label,
                                    originalTemplate = originalTemplate,
                                    candidateTemplate = template,
                                )
                            ) {
                                return@firstNotNullOfOrNull null
                            }
                            syntheticAuxiliaryOptimizationResult(
                                optimizationInputBase.originalPrompt,
                                template,
                                label,
                            )
                        }
                if (chosen == null) {
                    val err = primaryResult.exceptionOrNull()
                    logger.warn("Optimization failed (suggest mode)", err)
                    throw PromptOptimizationException(
                        message = "Optimization failed: ${err?.message ?: "unknown"}",
                        cause = err,
                    )
                }
                val metaOut = chosen.metadata.toMutableMap()
                metaOut["optimizationStatus"] = chosen.status.name
                return@coroutineScope OptimizationRoundArtifacts(
                    optimizationResult = chosen.copy(metadata = metaOut),
                    optimizedExperimentResult = null,
                    improvement = null,
                    proposedPromptTemplateSnapshot = chosen.optimizedPrompt?.template,
                )
            }

            val optPrimary = primaryResult.getOrNull()
            val promptPrimary = optPrimary?.optimizedPrompt
            val originalTemplate = optimizationInputBase.originalPrompt.template

            val evaluatable: List<Triple<String, OptimizationResult, Prompt>> =
                buildList {
                    if (optPrimary != null && promptPrimary != null) {
                        if (
                            isCandidateTemplatePlaceholderValid(
                                candidateLabel = "primary",
                                originalTemplate = originalTemplate,
                                candidateTemplate = promptPrimary.template,
                            )
                        ) {
                            add(Triple("primary", optPrimary, promptPrimary))
                        }
                    }
                    for ((label, template) in auxiliaryCandidates) {
                        if (
                            !isCandidateTemplatePlaceholderValid(
                                candidateLabel = label,
                                originalTemplate = originalTemplate,
                                candidateTemplate = template,
                            )
                        ) {
                            continue
                        }
                        val auxPrompt =
                            Prompt(
                                optimizationInputBase.originalPrompt.id,
                                optimizationInputBase.originalPrompt.name,
                                template,
                                optimizationInputBase.originalPrompt.variables,
                            )
                        val auxResult =
                            syntheticAuxiliaryOptimizationResult(
                                optimizationInputBase.originalPrompt,
                                template,
                                label,
                            )
                        add(Triple(label, auxResult, auxPrompt))
                    }
                }

            // Если все кандидаты отклонены или не сгенерированы — zero-order fallback
            if (evaluatable.isEmpty()) {
                val err = primaryResult.exceptionOrNull()
                logger.warn("No evaluatable prompt candidates, attempting zero-order fallback")
                val problemDescription = buildProblemDescription(baselineForImprovementMetrics, loadedConfiguration)
                val fallbackInstruction = zeroOrderFallbackGenerator.generate(problemDescription)
                if (
                    fallbackInstruction != null &&
                    isCandidateTemplatePlaceholderValid(
                        candidateLabel = "zeroorder_fallback",
                        originalTemplate = originalTemplate,
                        candidateTemplate = fallbackInstruction,
                    )
                ) {
                    logger.info("Zero-order fallback succeeded for empty evaluatable list")
                    val fallbackResult = syntheticAuxiliaryOptimizationResult(
                        optimizationInputBase.originalPrompt,
                        fallbackInstruction,
                        "zeroorder_fallback",
                    )
                    return@coroutineScope applyHarnessEvaluationAndPostProcess(
                        baselineForImprovementMetrics = baselineForImprovementMetrics,
                        optimizationConfig = optimizationConfig,
                        configurationSource = configurationSource,
                        iterationRound = iterationRound,
                        optimizationResult = fallbackResult,
                    )
                }
                logger.warn("Zero-order fallback also failed")
                throw PromptOptimizationException(
                    message = "Optimization failed: ${err?.message ?: "no optimized prompt"}",
                    cause = err,
                )
            }

            if (evaluatable.size == 1) {
                val only = evaluatable.single()
                return@coroutineScope applyHarnessEvaluationAndPostProcess(
                    baselineForImprovementMetrics = baselineForImprovementMetrics,
                    optimizationConfig = optimizationConfig,
                    configurationSource = configurationSource,
                    iterationRound = iterationRound,
                    optimizationResult = only.second,
                )
            }

            val evaluated =
                evaluatable.map { (label, result, prompt) ->
                    val exp =
                        runHarnessWithOptimizedPrompt(
                            baselineForImprovementMetrics,
                            prompt,
                            configurationSource,
                        )
                    Triple(label, result, exp)
                }
            val winner = evaluated.maxByOrNull { it.third.metrics.averageScore }!!
            val winnerOptimizationResult =
                if (winner.first == "primary") {
                    winner.second
                } else {
                    winner.second.copy(
                        reasoning = "${winner.first} candidate selected (higher harness averageScore)",
                        metadata = winner.second.metadata + mapOf("winner" to winner.first),
                    )
                }
            return@coroutineScope completeApplyIterationAfterHarness(
                baselineForImprovementMetrics = baselineForImprovementMetrics,
                optimizationConfig = optimizationConfig,
                configurationSource = configurationSource,
                iterationRound = iterationRound,
                optimizationResult = winnerOptimizationResult,
                optimizedExperimentResult = winner.third,
            )
        }

    private fun isCandidateTemplatePlaceholderValid(
        candidateLabel: String,
        originalTemplate: String,
        candidateTemplate: String,
    ): Boolean {
        val missing = missingTemplatePlaceholders(originalTemplate, candidateTemplate)
        if (missing.isEmpty()) return true
        logger.warn(
            "Skipping {} prompt candidate: missing template placeholders {}",
            candidateLabel,
            missing,
        )
        return false
    }

    private fun syntheticAuxiliaryOptimizationResult(
        original: Prompt,
        template: String,
        candidateSource: String,
    ): OptimizationResult =
        OptimizationResult(
            originalPrompt = original,
            optimizedPrompt =
                Prompt(
                    original.id,
                    original.name,
                    template,
                    original.variables,
                ),
            suggestions = emptyList(),
            confidence = 0.62,
            reasoning = "$candidateSource candidate (primary optimizer unavailable or auxiliary route)",
            metadata = mapOf("candidateSource" to candidateSource),
            executionTimeMs = 0L,
            status = OptimizationStatus.APPLIED,
        )

    private suspend fun runHarnessWithOptimizedPrompt(
        baselineForImprovementMetrics: ExperimentResult,
        optimizedPrompt: Prompt,
        configurationSource: String?,
    ): ExperimentResult {
        val modifiedYaml =
            createModifiedConfiguration(
                experimentResult = baselineForImprovementMetrics,
                optimizedPrompt = optimizedPrompt,
                configurationSourceFallback = configurationSource,
            )
        return experimentRunner.runExperiment(
            configurationSource = modifiedYaml,
            agentConfig = baselineForImprovementMetrics.agentConfig,
        )
    }

    private suspend fun applyHarnessEvaluationAndPostProcess(
        baselineForImprovementMetrics: ExperimentResult,
        optimizationConfig: OptimizationConfig,
        configurationSource: String?,
        iterationRound: Int?,
        optimizationResult: OptimizationResult,
    ): OptimizationRoundArtifacts {
        if (
            optimizationConfig.mode == OptimizationMode.APPLY &&
            optimizationResult.optimizedPrompt != null
        ) {
            logger.info(
                "Re-running harness with optimized prompt `${optimizationResult.optimizedPrompt!!.id}`",
            )
            try {
                val optimizedExperimentResult =
                    runHarnessWithOptimizedPrompt(
                        baselineForImprovementMetrics,
                        optimizationResult.optimizedPrompt!!,
                        configurationSource,
                    )
                return completeApplyIterationAfterHarness(
                    baselineForImprovementMetrics = baselineForImprovementMetrics,
                    optimizationConfig = optimizationConfig,
                    configurationSource = configurationSource,
                    iterationRound = iterationRound,
                    optimizationResult = optimizationResult,
                    optimizedExperimentResult = optimizedExperimentResult,
                )
            } catch (e: Exception) {
                logger.warn("Failed to evaluate optimized prompt in harness", e)
                val failedMeta = optimizationResult.metadata.toMutableMap()
                failedMeta["optimizationStatus"] = OptimizationStatus.FAILED.name
                failedMeta["rollbackReason"] = "harness_evaluation_failed: ${e.message}"
                val snapshotBeforeClear = optimizationResult.optimizedPrompt?.template
                return OptimizationRoundArtifacts(
                    optimizationResult =
                        optimizationResult.copy(
                            optimizedPrompt = null,
                            status = OptimizationStatus.FAILED,
                            metadata = failedMeta,
                        ),
                    optimizedExperimentResult = null,
                    improvement = null,
                    proposedPromptTemplateSnapshot = snapshotBeforeClear,
                )
            }
        }

        val metaOut = optimizationResult.metadata.toMutableMap()
        metaOut["optimizationStatus"] = optimizationResult.status.name
        return OptimizationRoundArtifacts(
            optimizationResult = optimizationResult.copy(metadata = metaOut),
            optimizedExperimentResult = null,
            improvement = null,
            proposedPromptTemplateSnapshot = optimizationResult.optimizedPrompt?.template,
        )
    }

    private suspend fun completeApplyIterationAfterHarness(
        baselineForImprovementMetrics: ExperimentResult,
        optimizationConfig: OptimizationConfig,
        configurationSource: String?,
        iterationRound: Int?,
        optimizationResult: OptimizationResult,
        optimizedExperimentResult: ExperimentResult,
    ): OptimizationRoundArtifacts {
        val proposedPromptTemplateSnapshot = optimizationResult.optimizedPrompt?.template
        val improvement =
            calculateImprovement(
                baseline = baselineForImprovementMetrics,
                optimized = optimizedExperimentResult,
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

        applyIterationCountersAndMutationEvolve(
            optimizationConfig = optimizationConfig,
            baselineAverageScore = baselineForImprovementMetrics.metrics.averageScore,
            optimizedAverageScore = optimizedExperimentResult.metrics.averageScore,
        )

        val modifiedYaml =
            createModifiedConfiguration(
                experimentResult = baselineForImprovementMetrics,
                optimizedPrompt = optimizationResult.optimizedPrompt!!,
                configurationSourceFallback = configurationSource,
            )

        recordOptimizationIterationBaselinesIfRequested(
            modifiedYaml = modifiedYaml,
            optimizationConfig = optimizationConfig,
            iterationRound = iterationRound,
            promptForIteration = finalOptimizedPrompt ?: optimizationResult.optimizedPrompt,
            optimizedExperimentResult = optimizedExperimentResult,
        )

        return OptimizationRoundArtifacts(
            optimizationResult = tagged,
            optimizedExperimentResult = optimizedExperimentResult,
            improvement = improvement,
            proposedPromptTemplateSnapshot = proposedPromptTemplateSnapshot,
        )
    }

    private suspend fun applyIterationCountersAndMutationEvolve(
        optimizationConfig: OptimizationConfig,
        baselineAverageScore: Double,
        optimizedAverageScore: Double,
    ) {
        totalIterations++
        if (optimizedAverageScore > baselineAverageScore) {
            successfulIterations++
        }
        if (optimizationConfig.evolveMutationPrompt &&
            totalIterations % optimizationConfig.mutationEvolveEveryNIterations == 0
        ) {
            currentMutationPrompt =
                mutationPromptEvolver.evolve(
                    currentMutationPrompt,
                    successfulIterations.toDouble() / totalIterations,
                )
        }
    }

    private fun extractJudgeExplanations(experimentResult: ExperimentResult): List<JudgeExplanation> {
        val suiteResults =
            experimentResult.runs.firstOrNull { it.success && it.result != null }?.result?.results.orEmpty()
        return suiteResults
            .filter { it.testCase.evaluatorType == "llm-judge" }
            .map {
                JudgeExplanation(
                    explanation = it.evaluationResult.explanation,
                    score = it.evaluationResult.score,
                )
            }
    }

    private fun buildProblemDescription(
        experimentResult: ExperimentResult,
        loadedConfiguration: TestConfiguration,
    ): String {
        val name = loadedConfiguration.suiteMetadata.name.trim()
        val parts = mutableListOf<String>()
        if (name.isNotBlank() && name != "Unnamed Test Suite") {
            parts += name
        }
        val suiteResults =
            experimentResult.runs.firstOrNull { it.success && it.result != null }?.result?.results.orEmpty()
        if (suiteResults.isNotEmpty()) {
            parts +=
                suiteResults.joinToString("\n") { tr ->
                    "${tr.testCase.promptId}: expected=${tr.testCase.expected.take(400)}"
                }
        }
        return parts.joinToString("\n\n").ifBlank {
            "Improve the assistant prompt for this evaluation suite."
        }
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

    private fun cycleStoppedIterationReportRow(
        round: Int,
        chainBaseline: ExperimentResult,
    ): OptimizationIterationReportRow =
        OptimizationIterationReportRow(
            iteration = round,
            proposedPromptTemplate = "—",
            scoreBefore = chainBaseline.metrics.averageScore,
            scoreAfter = null,
            rolledBack = false,
            rollbackReason = null,
        )

    private fun buildIterationReportRow(
        iteration: Int,
        baselineBeforeStep: ExperimentResult,
        artifacts: OptimizationRoundArtifacts,
    ): OptimizationIterationReportRow {
        val templateSource =
            artifacts.proposedPromptTemplateSnapshot
                ?: artifacts.optimizationResult.optimizedPrompt?.template
        val template =
            if (templateSource.isNullOrBlank()) "—" else templateSource
        val imp = artifacts.improvement
        return OptimizationIterationReportRow(
            iteration = iteration,
            proposedPromptTemplate = template,
            scoreBefore = baselineBeforeStep.metrics.averageScore,
            scoreAfter = artifacts.optimizedExperimentResult?.metrics?.averageScore,
            rolledBack = imp?.rolledBack == true,
            rollbackReason = if (imp?.rolledBack == true) imp.rollbackReason else null,
        )
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

    private suspend fun recordOptimizationIterationBaselinesIfRequested(
        modifiedYaml: String,
        optimizationConfig: OptimizationConfig,
        iterationRound: Int?,
        promptForIteration: Prompt?,
        optimizedExperimentResult: ExperimentResult,
    ) {
        val promptTemplate = promptForIteration?.template ?: return
        val loaded =
            runCatching { testConfigurationRepository.loadConfiguration(modifiedYaml) }
                .getOrElse { return }
        val reg = loaded.regressionConfiguration ?: return
        if (reg.baselineMode != BaselinePersistenceMode.RECORD) return

        val run =
            optimizedExperimentResult.runs.firstOrNull { it.success && it.result != null }
                ?: return
        val suiteResult = run.result!!
        if (suiteResult.results.any { it.infrastructureError }) {
            logger.warn(
                "Skipping optimization baseline RECORD: suite run has infrastructure errors",
            )
            return
        }

        val iterationIndex = iterationRound ?: 1
        val promptVersion =
            PromptVersion(
                iteration = iterationIndex,
                prompt = promptTemplate,
                score = optimizedExperimentResult.metrics.averageScore,
                mutationPrompt = currentMutationPrompt,
            )
        val suiteId = BaselineKeys.suiteId(modifiedYaml, loaded.suiteMetadata)
        for (tr in suiteResult.results) {
            val testCaseId = TestCaseIdentifiers.stableTestCaseId(tr.testCase)
            baselineRepository.saveBaseline(
                suiteId,
                testCaseId,
                tr.toBaselineEntry(promptVersion = promptVersion),
            )
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