package ru.yandex.diploma.aiplatform.infrastructure.llm

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import ru.yandex.diploma.aiplatform.config.OpenAiError
import ru.yandex.diploma.aiplatform.config.OpenAiMessage
import ru.yandex.diploma.aiplatform.config.OpenAiRequest
import ru.yandex.diploma.aiplatform.config.OpenAiResponse
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.LlmProviderException
import ru.yandex.diploma.aiplatform.infrastructure.llm.config.OpenRouterConfig
import java.time.Duration

@Component
@ConditionalOnProperty(value = ["llm.providers.openrouter.enabled"], havingValue = "true")
class OpenRouterLlmProvider(
    private val config: OpenRouterConfig
) : LlmProvider {

    private val apiKey: String get() = config.apiKey
    private val baseUrl: String get() = config.baseUrl
    private val defaultModel: String get() = config.defaultModel
    private val maxRetries: Int get() = config.maxRetries
    private val retryDelay: Duration get() = config.retryDelay

    private val logger = LoggerFactory.getLogger(OpenRouterLlmProvider::class.java)
    override val providerId: String = "openrouter"

    private val httpClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer $apiKey")
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("User-Agent", "AI-Platform/1.0")
            .build()
    }

    override suspend fun generate(request: LlmRequest): LlmResponse {
        validateConfiguration()
        val openAiRequest = mapToOpenAiRequest(request)
        return executeWithRetry { performHttpCall(openAiRequest) }
    }

    override suspend fun isHealthy(): Boolean {
        return try {
            if (!config.isConfigured()) {
                logger.warn("OpenRouter provider is not configured")
                return false
            }
            val healthCheckRequest = OpenAiRequest(
                model = defaultModel,
                messages = listOf(OpenAiMessage("user", "ping")),
                maxTokens = 1
            )
            performHttpCall(healthCheckRequest)
            logger.debug("OpenRouter health check passed")
            true
        } catch (e: Exception) {
            logger.warn("OpenRouter health check failed: ${e.message}")
            false
        }
    }

    private suspend fun performHttpCall(request: OpenAiRequest): LlmResponse {
        try {
            logger.debug("OpenRouter request: model=${request.model}, messages=${request.messages.size}")
            val response = httpClient
                .post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .awaitBody<OpenAiResponse>()
            logger.debug("OpenRouter response: tokens=${response.usage.totalTokens}")
            return mapToLlmResponse(response)
        } catch (e: WebClientResponseException) {
            logger.error("OpenRouter HTTP error: ${e.statusCode} - ${e.responseBodyAsString}")
            throw handleHttpError(e)
        } catch (e: Exception) {
            logger.error("Unexpected error during OpenRouter API call", e)
            throw LlmProviderException(
                message = "OpenRouter API call failed: ${e.message}",
                cause = e,
                providerId = providerId
            )
        }
    }

    private fun mapToOpenAiRequest(request: LlmRequest): OpenAiRequest {
        val messages = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { add(OpenAiMessage("system", it)) }
            add(OpenAiMessage("user", request.prompt))
        }
        val modelToUse = request.model ?: defaultModel
        return OpenAiRequest(
            model = modelToUse,
            messages = messages,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            topP = request.topP,
            frequencyPenalty = request.frequencyPenalty,
            presencePenalty = request.presencePenalty
        )
    }

    private fun mapToLlmResponse(response: OpenAiResponse): LlmResponse {
        val choice = response.choices.firstOrNull()
            ?: throw LlmProviderException(
                message = "OpenRouter response contains no choices",
                providerId = providerId
            )
        return LlmResponse(
            content = choice.message.content,
            tokensUsed = response.usage.totalTokens,
            model = response.model,
            finishReason = choice.finishReason,
            metadata = mapOf(
                "provider" to providerId,
                "openrouter_id" to response.id,
                "created" to response.created,
                "prompt_tokens" to response.usage.promptTokens,
                "completion_tokens" to response.usage.completionTokens,
                "total_tokens" to response.usage.totalTokens
            )
        )
    }

    private fun handleHttpError(e: WebClientResponseException): LlmProviderException {
        return when (e.statusCode) {
            HttpStatus.UNAUTHORIZED -> LlmProviderException(
                message = "OpenRouter API authentication failed. Check your API key.",
                cause = e,
                providerId = providerId
            )
            HttpStatus.TOO_MANY_REQUESTS -> LlmProviderException(
                message = "OpenRouter API rate limit exceeded. Please try again later.",
                cause = e,
                providerId = providerId
            )
            HttpStatus.BAD_REQUEST -> LlmProviderException(
                message = "OpenRouter API request error: ${parseErrorMessage(e)}",
                cause = e,
                providerId = providerId
            )
            HttpStatus.INTERNAL_SERVER_ERROR -> LlmProviderException(
                message = "OpenRouter API server error. Please try again later.",
                cause = e,
                providerId = providerId
            )
            else -> LlmProviderException(
                message = "OpenRouter API call failed: ${e.statusCode} - ${e.responseBodyAsString}",
                cause = e,
                providerId = providerId
            )
        }
    }

    private fun parseErrorMessage(e: WebClientResponseException): String {
        return try {
            val errorResponse = e.getResponseBodyAs(OpenAiError::class.java)
            errorResponse?.error?.message ?: "Bad request"
        } catch (parseError: Exception) {
            logger.warn("Failed to parse OpenRouter error response", parseError)
            "Bad request: ${e.responseBodyAsString}"
        }
    }

    private suspend fun <T> executeWithRetry(operation: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return operation()
            } catch (e: LlmProviderException) {
                if (shouldNotRetry(e)) throw e
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(calculateBackoffDelay(attempt))
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(calculateBackoffDelay(attempt))
                }
            }
        }
        throw LlmProviderException(
            message = "OpenRouter API call failed after $maxRetries attempts: ${lastException?.message}",
            cause = lastException,
            providerId = providerId
        )
    }

    private fun shouldNotRetry(e: LlmProviderException): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("authentication") ||
            message.contains("bad request") ||
            message.contains("unauthorized")
    }

    private fun calculateBackoffDelay(attempt: Int): Long =
        retryDelay.toMillis() * (1L shl attempt)

    private fun validateConfiguration() {
        if (!config.isConfigured()) {
            throw LlmProviderException(
                message = "OpenRouter provider is not configured. Set llm.providers.openrouter.enabled=true and API key.",
                providerId = providerId
            )
        }
    }
}
