package ru.yandex.diploma.aiplatform.infrastructure.optimizer

import org.springframework.stereotype.Component
import ru.yandex.diploma.aiplatform.domain.model.OptimizerType
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizer
import ru.yandex.diploma.aiplatform.domain.service.PromptOptimizerRegistry

@Component
class DefaultPromptOptimizerRegistry(
    private val optimizers: List<PromptOptimizer>
) : PromptOptimizerRegistry {
    
    private val optimizerMap = optimizers.associateBy { it.optimizerType }
    
    override fun getOptimizer(type: OptimizerType): PromptOptimizer? {
        return optimizerMap[type]
    }
    
    override fun registerOptimizer(optimizer: PromptOptimizer) {
        throw UnsupportedOperationException("Dynamic registration not supported in this implementation")
    }
    
    override fun getAvailableOptimizers(): List<OptimizerType> {
        return optimizerMap.keys.toList()
    }
}