package io.github.romanvht.byedpi.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeneticEngineTest {

    private val parents = listOf(
        "-o1 -a1 -r-5+se",
        "-s3:5+sm -Qr",
        "-f-200 -d7 -t8",
        "--split 3 --disorder 7",
        "-q1:3 -Mh -n\"www.google.com\"",
    )

    @Test
    fun parseKeepsShortAndLongForms() {
        assertEquals("-f-200 -s3:5+sm -Qr -a1", GeneticEngine.parse("-f-200 -s3:5+sm -Qr -a1").render())
        assertEquals("-s3 -d7", GeneticEngine.parse("--split 3 --disorder 7").render())
        assertEquals("-o1 -a1 -r-5+se", GeneticEngine.parse("-o1 -a1 -r-5+se").render())
        assertEquals("-t8 -S", GeneticEngine.parse("--ttl=8 --md5sig").render())
    }

    @Test
    fun generationHasExpectedSize() {
        val next = GeneticEngine.nextGeneration(parents, 3, 1, 2, Random(1))
        assertEquals(5 * (1 + 3 + 1 + 2), next.size)
        assertEquals(next.size, next.distinct().size)
        parents.map { GeneticEngine.parse(it).render() }.forEach { assertTrue(it in next) }
    }

    @Test
    fun deletedParametersAreDifferentWithinParent() {
        val parent = "-s3 -d7 -t8 -Qr"
        val next = GeneticEngine.nextGeneration(listOf(parent), 0, 2, 0, Random(7))
        assertEquals(3, next.size)

        val parentKeys = GeneticEngine.parse(parent).arguments.mapNotNull { it.key }.toSet()
        val removed = next.drop(1).map { command ->
            val keys = GeneticEngine.parse(command).arguments.mapNotNull { it.key }.toSet()
            parentKeys - keys
        }

        assertTrue(removed.all { it.size == 1 })
        assertNotEquals(removed[0], removed[1])
    }

    @Test
    fun everyGeneratedStrategyCanBeParsedAgain() {
        val next = GeneticEngine.nextGeneration(parents, 3, 1, 2, Random(42))
        next.forEach { command ->
            val parsed = GeneticEngine.parse(command)
            assertTrue("empty strategy: $command", parsed.arguments.isNotEmpty())
            assertEquals(parsed.render(), GeneticEngine.parse(parsed.render()).render())
        }
    }

    @Test
    fun fakeDataWithSpacesSurvivesRoundTrip() {
        val command = "--fake-data \":GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n\" -f-1"
        val rendered = GeneticEngine.parse(command).render()
        assertEquals(rendered, GeneticEngine.parse(rendered).render())
        assertEquals(
            ":GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
            GeneticEngine.parse(rendered).arguments.first().value,
        )
    }

    @Test
    fun unknownOptionsArePreserved() {
        val command = "-s3 --unknown-option value -d7"
        val rendered = GeneticEngine.parse(command).render()
        assertTrue(rendered.contains("--unknown-option"))
        assertEquals(rendered, GeneticEngine.parse(rendered).render())
    }

    @Test
    fun zeroMutationsKeepsOnlyParents() {
        val next = GeneticEngine.nextGeneration(parents, 0, 0, 0, Random(3))
        assertEquals(parents.map { GeneticEngine.parse(it).render() }, next)
    }

    @Test
    fun generationAlwaysKeepsDesyncParameter() {
        repeat(50) { seed ->
            val next = GeneticEngine.nextGeneration(parents, 6, 3, 4, Random(seed))
            next.forEach { command ->
                val keys = GeneticEngine.parse(command).arguments.mapNotNull { it.key }
                assertTrue("no desync parameter: $command", keys.any { it in setOf('s', 'd', 'o', 'q', 'f') })
            }
        }
    }
}
