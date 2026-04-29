package ru.yandex.diploma.aiplatform.application.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.yandex.diploma.aiplatform.domain.model.*
import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration
import java.time.Instant

@Service
class ExperimentRunner(
    private val runTestSuiteUseCase: RunTestSuiteUseCase
) {
    private val logger = LoggerFactory.getLogger(ExperimentRunner::class.java)
    
    suspend fun runExperiment(
        configurationSource: String,
        agentConfig: AgentConfig = AgentConfig.create("default", "Ты полезный ассистент.")
    ): ExperimentResult {
        val startTime = System.currentTimeMillis()
        logger.info(
            "Запуск эксперимента с агентом: ${agentConfig.name}, " +
            "модель: ${agentConfig.model ?: "default"}, " +
            "температура: ${agentConfig.temperature}"
        )
        
        return try {
            val result = runTestSuiteUseCase.execute(
                configurationSource = configurationSource,
                executionConfig = agentConfig.toExecutionConfig()
            )
            
            val endTime = System.currentTimeMillis()
            val executionTimeMs = endTime - startTime
            
            val experimentRun = ExperimentRun(
                agentName = agentConfig.name,
                model = agentConfig.model ?: "default",
                temperature = agentConfig.temperature,
                result = result,
                success = true,
                error = null,
                executionTimeMs = executionTimeMs
            )
            
            val metrics = calculateMetrics(listOf(experimentRun))
            
            logger.info("Эксперимент успешно завершен за ${executionTimeMs}мс")
            
            ExperimentResult(
                agentConfig = agentConfig,
                runs = listOf(experimentRun),
                metrics = metrics,
                executionTimeMs = executionTimeMs,
                timestamp = Instant.now().toString(),
                configurationSource = configurationSource
            )
            
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val executionTimeMs = endTime - startTime
            
            logger.error("Эксперимент не удался для агента: ${agentConfig.name}", e)
            
            val failedRun = ExperimentRun(
                agentName = agentConfig.name,
                model = agentConfig.model ?: "default",
                temperature = agentConfig.temperature,
                result = null,
                success = false,
                error = e.message,
                executionTimeMs = executionTimeMs
            )
            
            val metrics = calculateMetrics(listOf(failedRun))
            
            ExperimentResult(
                agentConfig = agentConfig,
                runs = listOf(failedRun),
                metrics = metrics,
                executionTimeMs = executionTimeMs,
                timestamp = Instant.now().toString(),
                configurationSource = configurationSource
            )
        }
    }
    
    suspend fun runMultiAgentExperiment(
        configurationSource: String,
        agentConfigs: List<AgentConfig>
    ): ExperimentResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        logger.info("Запуск мульти-агентного эксперимента с ${agentConfigs.size} агентами")
        
        // Запускаем эксперименты параллельно для каждой конфигурации агента
        val runs = agentConfigs.map { agentConfig ->
            async {
                try {
                    logger.debug("Запуск теста для агента: ${agentConfig.name} с моделью: ${agentConfig.model}")
                    
                    val result = runTestSuiteUseCase.execute(
                        configurationSource = configurationSource,
                        executionConfig = agentConfig.toExecutionConfig()
                    )
                    
                    ExperimentRun(
                        agentName = agentConfig.name,
                        model = agentConfig.model ?: "default",
                        temperature = agentConfig.temperature,
                        result = result,
                        success = true,
                        error = null,
                        executionTimeMs = result.executionTimeMs
                    )
                } catch (e: Exception) {
                    logger.error("Запуск эксперимента не удался для агента: ${agentConfig.name}", e)
                    ExperimentRun(
                        agentName = agentConfig.name,
                        model = agentConfig.model ?: "default",
                        temperature = agentConfig.temperature,
                        result = null,
                        success = false,
                        error = e.message,
                        executionTimeMs = 0L
                    )
                }
            }
        }.awaitAll()
        
        val endTime = System.currentTimeMillis()
        val executionTimeMs = endTime - startTime
        val metrics = calculateMetrics(runs)
        
        logger.info("Мульти-агентный эксперимент завершен: ${runs.size} запусков за ${executionTimeMs}мс")
        
        ExperimentResult(
            agentConfig = agentConfigs.firstOrNull() ?: AgentConfig.create("multi", "Мульти-агентный эксперимент"),
            runs = runs,
            metrics = metrics,
            executionTimeMs = executionTimeMs,
            timestamp = Instant.now().toString(),
            configurationSource = configurationSource
        )
    }
    
    private fun calculateMetrics(runs: List<ExperimentRun>): ExperimentMetrics {
        val successfulRuns = runs.filter { it.success && it.result != null }
        
        if (successfulRuns.isEmpty()) {
            return ExperimentMetrics(
                totalRuns = runs.size,
                successfulRuns = 0,
                failedRuns = runs.size,
                averageLatency = 0.0,
                averageScore = 0.0
            )
        }
        
        val allResults = successfulRuns.mapNotNull { it.result }
        val averageLatency = allResults.map { it.executionTimeMs }.average().let { avg ->
            if (avg.isNaN()) 0.0 else avg
        }
        val scores = allResults.flatMap { it.results }.map { it.evaluationResult.score }
        val averageScore = scores.takeUnless { it.isEmpty() }?.average()?.let { avg ->
            if (avg.isNaN()) 0.0 else avg
        } ?: 0.0
        
        return ExperimentMetrics(
            totalRuns = runs.size,
            successfulRuns = successfulRuns.size,
            failedRuns = runs.size - successfulRuns.size,
            averageLatency = averageLatency,
            averageScore = averageScore
        )
    }
}

data class ExperimentRun(
    val agentName: String,
    val model: String,
    val temperature: Double,
    val result: TestSuiteResult?,
    val success: Boolean,
    val error: String?,
    val executionTimeMs: Long = 0L
)

data class ExperimentResult(
    val agentConfig: AgentConfig,
    val runs: List<ExperimentRun>,
    val metrics: ExperimentMetrics,
    val executionTimeMs: Long,
    val timestamp: String,
    val configurationSource: String? = null
)

data class ExperimentMetrics(
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val averageLatency: Double,
    val averageScore: Double
)