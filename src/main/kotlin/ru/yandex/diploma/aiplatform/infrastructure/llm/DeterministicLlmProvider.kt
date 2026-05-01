package ru.yandex.diploma.aiplatform.infrastructure.llm

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider

@Component
@ConditionalOnProperty(value = ["llm.providers.deterministic.enabled"], havingValue = "true")
class DeterministicLlmProvider : LlmProvider {
    
    override val providerId: String = "deterministic"
    
    override suspend fun generate(request: LlmRequest): LlmResponse {
        val promptHash = request.prompt.hashCode().toString().takeLast(4)
        val baseContent = generateDeterministicContent(request.prompt)
        
        return LlmResponse(
            content = baseContent,
            tokensUsed = calculateDeterministicTokens(request.prompt),
            model = request.model ?: "deterministic-model-v1",
            finishReason = "stop",
            metadata = mapOf(
                "provider" to providerId,
                "prompt_hash" to promptHash,
                "deterministic" to true,
                "temperature" to (request.temperature ?: 0.0),
                "max_tokens" to (request.maxTokens ?: 100)
            )
        )
    }
    
    override suspend fun isHealthy(): Boolean = true
    
    private fun generateDeterministicContent(prompt: String): String {
        return when {
            prompt.contains("ping", ignoreCase = true) -> "pong"
            prompt.contains("hello", ignoreCase = true) -> "Hello! I'm a deterministic AI assistant."
            prompt.contains("test", ignoreCase = true) -> "This is a deterministic response for testing purposes."
            prompt.contains("translate", ignoreCase = true) && prompt.contains("Hello") && prompt.contains("French") -> "Deterministic response generated for prompt: Translate Hello to French"
            prompt.contains("translate", ignoreCase = true) -> "This is a deterministic translation response."
            prompt.contains("architecture", ignoreCase = true) -> "Clean Architecture is a software design philosophy that emphasizes separation of concerns and dependency inversion."
            prompt.contains("explain", ignoreCase = true) -> "This is a deterministic explanation generated for testing consistency."
            prompt.contains("analyze", ignoreCase = true) -> "Analysis complete: This is a deterministic analytical response."
            prompt.contains("evaluate", ignoreCase = true) -> "Evaluation result: This response provides consistent evaluation output."
            prompt.contains("compare", ignoreCase = true) -> "Comparison result: Both options have been analyzed deterministically."
            prompt.contains("summarize", ignoreCase = true) -> "Summary: This is a deterministic summary of the provided content."
            prompt.length < 10 -> "Short prompt received. Deterministic response generated."
            prompt.length > 100 -> "Long prompt processed. Comprehensive deterministic response provided."
            else -> "Deterministic response generated for prompt: ${prompt.take(50)}${if (prompt.length > 50) "..." else ""}"
        }
    }
    
    private fun calculateDeterministicTokens(prompt: String): Int {
        val baseTokens = prompt.length / 4
        val responseTokens = 20
        return baseTokens + responseTokens
    }
}