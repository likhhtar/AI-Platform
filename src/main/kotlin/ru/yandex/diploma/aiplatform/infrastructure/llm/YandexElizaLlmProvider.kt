package ru.yandex.diploma.aiplatform.infrastructure.llm

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient as ReactorHttpClient
import ru.yandex.diploma.aiplatform.config.OpenAiError
import ru.yandex.diploma.aiplatform.config.OpenAiMessage
import ru.yandex.diploma.aiplatform.config.OpenAiRequest
import ru.yandex.diploma.aiplatform.config.OpenAiResponse
import ru.yandex.diploma.aiplatform.domain.model.LlmRequest
import ru.yandex.diploma.aiplatform.domain.model.LlmResponse
import ru.yandex.diploma.aiplatform.domain.provider.LlmProvider
import ru.yandex.diploma.aiplatform.domain.provider.LlmProviderException
import ru.yandex.diploma.aiplatform.infrastructure.llm.config.YandexElizaConfig
import java.time.Duration

@Component
@ConditionalOnProperty(value = ["llm.providers.yandex-eliza.enabled"], havingValue = "true")
class YandexElizaLlmProvider(
    private val config: YandexElizaConfig,
) : LlmProvider {

    private val oauthToken: String get() = config.oauthToken
    private val baseUrl: String get() = config.baseUrl
    private val defaultModel: String get() = config.defaultModel
    private val timeout: Duration get() = config.timeout
    private val maxRetries: Int get() = config.maxRetries
    private val retryDelay: Duration get() = config.retryDelay

    private val logger = LoggerFactory.getLogger(YandexElizaLlmProvider::class.java)
    override val providerId: String = "yandex-eliza"

    private val httpClient: WebClient by lazy {
        val connector =
            ReactorClientHttpConnector(
                ReactorHttpClient.create().responseTimeout(timeout),
            )
        WebClient.builder()
            .clientConnector(connector)
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "OAuth $oauthToken")
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("User-Agent", "AI-Platform/1.0")
            .build()
    }

    override suspend fun generate(request: LlmRequest): LlmResponse {
        validateConfiguration()

        val openAiRequest = mapToOpenAiRequest(request)
        val wallStart = System.currentTimeMillis()
        val model = openAiRequest.model
        return try {
            executeWithRetry {
                performHttpCall(openAiRequest)
            }.also {
                logger.info(
                    "llm_yandex_eliza_done model={} durationMs={} promptChars={} responseChars={}",
                    model,
                    System.currentTimeMillis() - wallStart,
                    request.prompt.length,
                    it.content.length,
                )
            }
        } catch (e: Exception) {
            logger.warn(
                "llm_yandex_eliza_failed model={} durationMs={} promptChars={}",
                model,
                System.currentTimeMillis() - wallStart,
                request.prompt.length,
                e,
            )
            throw e
        }
    }

    override suspend fun isHealthy(): Boolean {
        return try {
            if (!config.isConfigured()) {
                logger.warn("Yandex Eliza provider is not configured")
                return false
            }

            val healthCheckRequest = OpenAiRequest(
                model = defaultModel,
                messages = listOf(OpenAiMessage("user", "ping")),
                maxTokens = 1,
            )

            performHttpCall(healthCheckRequest)
            logger.debug("Yandex Eliza health check passed")
            true
        } catch (e: Exception) {
            logger.warn("Yandex Eliza health check failed: ${e.message}")
            false
        }
    }

    private suspend fun performHttpCall(request: OpenAiRequest): LlmResponse {
        try {
            logger.debug(
                "Sending request to Yandex Eliza API: model=${request.model}, messages=${request.messages.size}",
            )

            val envelope = httpClient
                .post()
                .uri("/openai/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .awaitBody<ElizaChatCompletionEnvelope>()

            val response = envelope.response
            logger.debug("Yandex Eliza API response received: tokens=${response.usage?.totalTokens ?: 0}")

            return mapToLlmResponse(response)
        } catch (e: WebClientResponseException) {
            logger.error("Yandex Eliza API HTTP error: ${e.statusCode} - ${e.responseBodyAsString}")
            throw handleHttpError(e)
        } catch (e: Exception) {
            logger.error("Unexpected error during Yandex Eliza API call", e)
            throw LlmProviderException(
                message = "Yandex Eliza API call failed: ${e.message}",
                cause = e,
                providerId = providerId,
            )
        }
    }

    private fun mapToOpenAiRequest(request: LlmRequest): OpenAiRequest {
        val messages = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemPrompt ->
                add(OpenAiMessage("system", systemPrompt))
            }

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
            presencePenalty = request.presencePenalty,
        )
    }

    private fun mapToLlmResponse(response: OpenAiResponse): LlmResponse {
        val choice = response.choices.firstOrNull()
            ?: throw LlmProviderException(
                message = "Yandex Eliza response contains no choices",
                providerId = providerId,
            )

        val usage = response.usage
        val metadata = buildMap<String, Any> {
            put("provider", providerId)
            response.id?.let { put("eliza_id", it) }
            response.created?.let { put("created", it) }
            usage?.let {
                put("prompt_tokens", it.promptTokens)
                put("completion_tokens", it.completionTokens)
                put("total_tokens", it.totalTokens)
            }
        }
        return LlmResponse(
            content = choice.message.content,
            tokensUsed = usage?.totalTokens,
            model = response.model,
            finishReason = choice.finishReason,
            metadata = metadata,
        )
    }

    private fun handleHttpError(e: WebClientResponseException): LlmProviderException {
        return when (e.statusCode) {
            HttpStatus.UNAUTHORIZED -> LlmProviderException(
                message = "Yandex Eliza API authentication failed. Check your OAuth token.",
                cause = e,
                providerId = providerId,
            )
            HttpStatus.TOO_MANY_REQUESTS -> LlmProviderException(
                message = "Yandex Eliza API rate limit exceeded. Please try again later.",
                cause = e,
                providerId = providerId,
            )
            HttpStatus.BAD_REQUEST -> {
                val errorMessage = parseErrorMessage(e)
                LlmProviderException(
                    message = "Yandex Eliza API request error: $errorMessage",
                    cause = e,
                    providerId = providerId,
                )
            }
            HttpStatus.INTERNAL_SERVER_ERROR -> LlmProviderException(
                message = "Yandex Eliza API server error. Please try again later.",
                cause = e,
                providerId = providerId,
            )
            else -> LlmProviderException(
                message = "Yandex Eliza API call failed: ${e.statusCode} - ${e.responseBodyAsString}",
                cause = e,
                providerId = providerId,
            )
        }
    }

    private fun parseErrorMessage(e: WebClientResponseException): String {
        return try {
            val errorResponse = e.getResponseBodyAs(OpenAiError::class.java)
            errorResponse?.error?.message ?: "Bad request"
        } catch (parseError: Exception) {
            logger.warn("Failed to parse Yandex Eliza error response", parseError)
            "Bad request: ${e.responseBodyAsString}"
        }
    }

    private suspend fun <T> executeWithRetry(operation: suspend () -> T): T {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return operation()
            } catch (e: LlmProviderException) {
                if (shouldNotRetry(e)) {
                    throw e
                }

                lastException = e
                if (attempt < maxRetries - 1) {
                    val delayMs = calculateBackoffDelay(attempt)
                    logger.warn(
                        "Yandex Eliza API call failed (attempt ${attempt + 1}/$maxRetries), retrying in ${delayMs}ms: ${e.message}",
                    )
                    delay(delayMs)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val delayMs = calculateBackoffDelay(attempt)
                    logger.warn(
                        "Yandex Eliza API call failed (attempt ${attempt + 1}/$maxRetries), retrying in ${delayMs}ms: ${e.message}",
                    )
                    delay(delayMs)
                }
            }
        }

        throw LlmProviderException(
            message = "Yandex Eliza API call failed after $maxRetries attempts: ${lastException?.message}",
            cause = lastException,
            providerId = providerId,
        )
    }

    private fun shouldNotRetry(e: LlmProviderException): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("authentication") ||
            message.contains("bad request") ||
            message.contains("unauthorized")
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        return retryDelay.toMillis() * (1L shl attempt)
    }

    private fun validateConfiguration() {
        if (!config.isConfigured()) {
            throw LlmProviderException(
                message = "Yandex Eliza provider is not configured. Please set llm.providers.yandex-eliza.enabled=true and provide OAuth token.",
                providerId = providerId,
            )
        }
    }
}
