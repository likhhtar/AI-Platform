package ru.yandex.diploma.aiplatform

import kotlinx.coroutines.runBlocking
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.application.usecase.RunTestSuiteUseCase
import ru.yandex.diploma.aiplatform.domain.model.ExecutionConfig
import kotlin.time.Duration.Companion.seconds

@Component
@Profile("!test")
class StartupRunner(
    private val runTestSuiteUseCase: RunTestSuiteUseCase
) : CommandLineRunner {

    override fun run(vararg args: String?) = runBlocking {
        println("STARTING REAL EXECUTION")
        
        try {
            val executionConfig = ExecutionConfig(
                enableParallelExecution = false,
                maxParallelism = 1,
                testTimeout = 30.seconds
            )
            
            println("Executing real LLM test...")
            
            val result = runTestSuiteUseCase.execute(
                configurationSource = "real-test-config.yaml",
                executionConfig = executionConfig
            )
            
            println("RESULT: $result")
            println("✅ Passed: ${result.passed}")
            println("❌ Failed: ${result.failed}")
            
            if (result.results.isNotEmpty()) {
                val firstResult = result.results.first()
                println("LLM Response: ${firstResult.llmResponse?.content}")
                println("Execution Time: ${firstResult.executionTimeMs}ms")
                println("Success: ${firstResult.success}")
            }
            
        } catch (e: Exception) {
            println("❌ ERROR during execution: ${e.message}")
            e.printStackTrace()
        }
    }
}