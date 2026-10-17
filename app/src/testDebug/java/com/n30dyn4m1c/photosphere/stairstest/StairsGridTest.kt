package com.n30dyn4m1c.photosphere.stairstest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cross-checks the Kotlin stairs grid against the coordinates the Python lab
 * proved (`tools/pano_proto/virtual_frames_stairs.py`, which wrote
 * `virtual_stairs_coords.json`).
 *
 * Debug-variant test only, in `src/testDebug`: it references `StairsGrid`
 * from `src/debug`, which the release variant never compiles. It asserts the
 * two sides agree on the same 62 poses, so a later edit to either that drops
 * or moves a target fails here instead of silently changing what the screen
 * asks the walker to shoot.
 */
class StairsGridTest {

    private fun resourceText(): String =
        javaClass.classLoader!!
            .getResourceAsStream("virtual_stairs_coords.json")!!
            .bufferedReader()
            .readText()

    /** (yaw, pitch) pairs from the JSON, as integer key pairs. */
    private fun jsonPoses(): Set<Pair<Int, Int>> {
        val pairRe = Regex(""""yaw":\s*(-?\d+(?:\.\d+)?),\s*"pitch":\s*(-?\d+(?:\.\d+)?)""")
        return pairRe.findAll(resourceText())
            .map { m ->
                key(m.groupValues[1].toFloat(), m.groupValues[2].toFloat())
            }
            .toSet()
    }

    /** Rounds to whole degrees and folds 180 onto -180 for comparison. */
    private fun key(yaw: Float, pitch: Float): Pair<Int, Int> {
        val y = yaw.roundToInt().let { if (it == 180) -180 else it }
        return y to pitch.roundToInt()
    }

    @Test
    fun planHas62TargetsAcrossFiveRowsAndTwoCapSets() {
        val plan = stairsPlan(0f)
        assertEquals(62, plan.size)
        assertEquals(7, plan.rings.size)
        val rows = plan.rings.take(5)
        rows.forEach { assertEquals(10, it.size) }
        assertEquals(6, plan.rings[5].size)
        assertEquals(6, plan.rings[6].size)
        // Indices are dense and in walk order.
        assertEquals((0 until 62).toList(), plan.targets.map { it.index })
    }

    @Test
    fun everyRowLeavesNoGapForTheLensFieldOfView() {
        val plan = stairsPlan(0f)
        for (ring in plan.rings.take(5)) {
            val yaws = ring.map { it.yawDegrees }.sorted()
            for (i in yaws.indices) {
                val next = yaws[(i + 1) % yaws.size]
                var step = next - yaws[i]
                if (step <= 0) step += 360f
                assertTrue(
                    "yaw step $step leaves a gap (H_FOV 49)",
                    step < 49f,
                )
            }
        }
    }

    @Test
    fun kotlinPlanMatchesPythonCoords() {
        val plan = stairsPlan(0f)
        val kotlinPoses = plan.targets
            .map { key(it.yawDegrees, it.pitchDegrees) }
            .toSet()
        assertEquals(62, kotlinPoses.size)
        assertEquals("Kotlin grid and Python coords disagree", jsonPoses(), kotlinPoses)
    }

    @Test
    fun capsSitNearThePolesAndRowsSitInside() {
        val plan = stairsPlan(0f)
        val caps = plan.targets.filter { it.isCap }
        assertEquals(12, caps.size)
        assertTrue(caps.any { abs(it.pitchDegrees) >= 75f })
        val rows = plan.targets.filterNot { it.isCap }
        assertEquals(50, rows.size)
        assertTrue(rows.all { abs(it.pitchDegrees) <= 45f })
    }

    @Test
    fun targetingGuidesandCompletes() {
        val plan = stairsPlan(0f)
        val first = plan.nextTarget(emptySet())
        assertEquals(0, first)
        assertTrue(plan.isOnTarget(0f, 0f, 0))
        // One degree off is still on target, ten is not.
        assertTrue(plan.isOnTarget(1f, -1f, 0))
        assertTrue(!plan.isOnTarget(10f, 0f, 0))
        // Turn right to the next yaw-36 target from straight ahead.
        assertEquals(36f, plan.turnTo(0f, 1), 0.01f)
        // Nearest pick ignores captured targets.
        val nearest = plan.nearestTarget(0f, 0f, setOf(0))
        assertNotNull(nearest)
        assertTrue(nearest != 0)
        // Complete set -> nothing left to ask for.
        assertNull(plan.nextTarget((0 until 62).toSet()))
    }
}
