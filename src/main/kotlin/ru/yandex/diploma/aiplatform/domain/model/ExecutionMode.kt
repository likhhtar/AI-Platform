package ru.yandex.diploma.aiplatform.domain.model

import ru.yandex.diploma.aiplatform.domain.repository.TestConfiguration

enum class ExecutionMode {
    DETERMINISTIC,
    REAL
}

object ExecutionModeRules {

    fun isDeterministicProviderId(providerId: String): Boolean =
        providerId.equals("deterministic", ignoreCase = true)

    fun inferFromAgents(agents: List<AgentConfig>): ExecutionMode? {
        val hasDeterministic = agents.any { isDeterministicProviderId(it.provider) }
        val hasNonDeterministic = agents.any { !isDeterministicProviderId(it.provider) }
        return when {
            hasDeterministic && hasNonDeterministic -> null
            hasDeterministic -> ExecutionMode.DETERMINISTIC
            else -> ExecutionMode.REAL
        }
    }

    fun validate(configuration: TestConfiguration): List<String> {
        val errors = mutableListOf<String>()
        val inferred = inferFromAgents(configuration.agents)
        val explicit = configuration.suiteMetadata.executionMode

        if (explicit != null && inferred != null && explicit != inferred) {
            errors.add(
                "suite.executionMode=$explicit conflicts with agent providers " +
                    "(inferred mode from agents alone would be $inferred). Align suite.executionMode with agent providers."
            )
        }

        val mode = explicit ?: inferred
        if (mode == null) {
            errors.add(
                "Cannot determine execution mode: agents mix deterministic and non-deterministic providers. " +
                    "Use only deterministic providers, only real providers, or set suite.executionMode explicitly."
            )
            return errors
        }

        when (mode) {
            ExecutionMode.DETERMINISTIC -> {
                configuration.agents.forEach { agent ->
                    if (!isDeterministicProviderId(agent.provider)) {
                        errors.add(
                            "DETERMINISTIC execution: agent '${agent.name}' uses forbidden provider '${agent.provider}' " +
                                "(only deterministic providers are allowed)."
                        )
                    }
                }
                validateOptimizationProvider(configuration, errors, allowOnlyDeterministic = true)
            }
            ExecutionMode.REAL -> {
                configuration.agents.forEach { agent ->
                    if (isDeterministicProviderId(agent.provider)) {
                        errors.add(
                            "REAL execution: agent '${agent.name}' must not use the deterministic provider."
                        )
                    }
                }
                validateOptimizationProvider(configuration, errors, allowOnlyDeterministic = false)
            }
        }

        return errors
    }

    private fun validateOptimizationProvider(
        configuration: TestConfiguration,
        errors: MutableList<String>,
        allowOnlyDeterministic: Boolean
    ) {
        val opt = configuration.optimizationConfig ?: return
        if (!opt.enabled || opt.type != OptimizerType.LLM) return
        val provider = opt.llmConfig?.provider
        if (provider.isNullOrBlank()) {
            errors.add("LLM optimizer is enabled but llm.provider is missing.")
            return
        }
        if (allowOnlyDeterministic && !isDeterministicProviderId(provider)) {
            errors.add(
                "DETERMINISTIC execution: LLM optimizer provider must be 'deterministic', got '$provider'."
            )
        }
        if (!allowOnlyDeterministic && isDeterministicProviderId(provider)) {
            errors.add(
                "REAL execution: LLM optimizer must not use the deterministic provider (got '$provider')."
            )
        }
    }
}
