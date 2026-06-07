package ru.yandex.diploma.aiplatform.application.optimization

import org.junit.jupiter.api.Test
import ru.yandex.diploma.aiplatform.domain.model.PromptVersion
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineageAwareOptimizationPromptBuilderTest {

    private val builder = LineageAwareOptimizationPromptBuilder()

    @Test
    fun `buildPrompt with non-empty lineage contains genotype lines with score brackets`() {
        val versions =
            listOf(
                PromptVersion(iteration = 1, prompt = "alpha", score = 0.42),
                PromptVersion(iteration = 2, prompt = "beta", score = null),
            )
        val result =
            builder.buildPrompt(
                currentPrompt = "CURRENT",
                mutationPrompt = "MUTATION_HINT",
                versions = versions,
            )

        assertTrue(result.contains("""v1: "alpha" -> score [0.42]"""))
        assertTrue(result.contains("""v2: "beta" -> score []"""))
    }

    @Test
    fun `buildPrompt sorts lineage by score ascending with null score last`() {
        val versions =
            listOf(
                PromptVersion(iteration = 3, prompt = "mid", score = 0.2),
                PromptVersion(iteration = 1, prompt = "no-score", score = null),
                PromptVersion(iteration = 2, prompt = "low", score = 0.1),
            )
        val result =
            builder.buildPrompt(
                currentPrompt = "X",
                mutationPrompt = "M",
                versions = versions,
            )

        val iLow = result.indexOf("""v1: "low"""")
        val iMid = result.indexOf("""v2: "mid"""")
        val iNull = result.indexOf("""v3: "no-score"""")
        assertTrue(iLow < iMid && iMid < iNull, "expected score order 0.1, 0.2, null")
    }

    @Test
    fun `buildPrompt with empty lineage does not contain history section`() {
        val result =
            builder.buildPrompt(
                currentPrompt = "CUR",
                mutationPrompt = "MUT",
                versions = emptyList(),
            )

        assertFalse(result.contains("History of previous attempts"))
    }

    @Test
    fun `mutationPrompt is present with empty and non-empty lineage`() {
        val mp = "Rewrite clearly"
        val emptyLineage =
            builder.buildPrompt(
                currentPrompt = "p1",
                mutationPrompt = mp,
                versions = emptyList(),
            )
        assertTrue(emptyLineage.startsWith("INSTRUCTION FOR OPTIMIZER: $mp"))

        val withLineage =
            builder.buildPrompt(
                currentPrompt = "p2",
                mutationPrompt = mp,
                versions =
                    listOf(
                        PromptVersion(iteration = 1, prompt = "q", score = 1.0),
                    ),
            )
        assertContains(withLineage, mp)
    }
}
