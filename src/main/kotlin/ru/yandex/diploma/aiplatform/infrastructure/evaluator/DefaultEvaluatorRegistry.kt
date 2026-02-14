package ru.yandex.diploma.aiplatform.infrastructure.evaluator

import ru.yandex.diploma.aiplatform.domain.evaluator.Evaluator
import ru.yandex.diploma.aiplatform.domain.evaluator.EvaluatorNotFoundException
import ru.yandex.diploma.aiplatform.domain.evaluator.EvaluatorRegistry
import org.springframework.stereotype.Component

@Component
class DefaultEvaluatorRegistry(
    evaluators: List<Evaluator>
) : EvaluatorRegistry {
    
    private val evaluatorMap = mutableMapOf<String, Evaluator>()
    
    init {
        evaluators.forEach { register(it) }
    }
    
    override fun register(evaluator: Evaluator) {
        evaluatorMap[evaluator.evaluatorType] = evaluator
    }
    
    override fun getEvaluator(type: String): Evaluator {
        return evaluatorMap[type] ?: throw EvaluatorNotFoundException(type)
    }
    
    override fun getAvailableTypes(): Set<String> {
        return evaluatorMap.keys.toSet()
    }
}