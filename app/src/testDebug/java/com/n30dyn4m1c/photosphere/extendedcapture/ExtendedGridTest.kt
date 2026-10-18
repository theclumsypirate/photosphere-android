package com.n30dyn4m1c.photosphere.extendedcapture

import com.n30dyn4m1c.photosphere.camera.FieldOfView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cross-checks the Kotlin extended capture grid against the coordinates the
 * Python lab proved (`tools/pano_proto/virtual_frames_stairs.py`, which wrote
 * `virtual_stairs_coords.json`).
 *
 * Debug-variant test only, in `src/testDebug`: it references `ExtendedGrid`
 * from `src/debug`, which the release variant never compiles. It asserts the
 * two sides agree on the same 62 poses, so a later edit to either that drops
 * or moves a target fails here instead of silently changing what the screen
 * asks the walker to shoot.
 */
class ExtendedGridTest {

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
        val plan = extendedReferencePlan(0f)
        assertEquals(62, plan.size)
        assertEquals(7, plan.rings.size)
        plan.rings.take(5).forEach { ring -> assertEquals(10, ring.count()) }
        assertEquals(6, plan.rings[5].count())
        assertEquals(6, plan.rings[6].count())
        // Indices are dense and in walk order.
        assertEquals((0 until 62).toList(), plan.targets.indices.toList())
    }

    @Test
    fun everyRowLeavesNoGapForTheLensFieldOfView() {
        val plan = extendedReferencePlan(0f)
        for (ring in plan.rings.take(5)) {
            val yaws = ring.map { plan.targets[it].yawDegrees }.sorted()
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
        val plan = extendedReferencePlan(0f)
        val kotlinPoses = plan.targets
            .map { key(it.yawDegrees, it.pitchDegrees) }
            .toSet()
        assertEquals(62, kotlinPoses.size)
        assertEquals("Kotlin grid and Python coords disagree", jsonPoses(), kotlinPoses)
    }

    @Test
    fun capsSitNearThePolesAndRowsSitInside() {
        val plan = extendedReferencePlan(0f)
        val capIndices = plan.targets.indices.filter { plan.isCap(it) }
        assertEquals(12, capIndices.size)
        assertTrue(capIndices.any { abs(plan.targets[it].pitchDegrees) >= 75f })
        val rows = plan.targets.filterIndexed { index, _ -> !plan.isCap(index) }
        assertEquals(50, rows.size)
        assertTrue(rows.all { abs(it.pitchDegrees) <= 45f })
    }

    @Test
    fun targetingGuidesAndRingsComplete() {
        val plan = extendedReferencePlan(0f)
        assertTrue(plan.isOnTarget(0f, 0f, 0))
        // One degree off is still on target, ten is not.
        assertTrue(plan.isOnTarget(1f, -1f, 0))
        assertFalse(plan.isOnTarget(10f, 0f, 0))
        // Turn right to the next yaw-36 target from straight ahead.
        assertEquals(36f, plan.turnTo(0f, 1), 0.01f)
        // The high ring sits twenty degrees above the horizon.
        assertEquals(-20f, plan.tiltTo(0f, 10), 0.01f)
        // Rings only complete when every member is captured.
        assertEquals(0, plan.completedRings(emptySet()))
        assertEquals(1, plan.completedRings(plan.rings[0].toSet()))
        assertEquals(62, plan.capturedCount((0 until 62).toSet()))
    }

    @Test
    fun referenceFieldOfViewMatchIsABand() {
        // The lens it was proven for.
        assertTrue(matchesReferenceFieldOfView(FieldOfView(49f, 63f)))
        assertTrue(matchesReferenceFieldOfView(FieldOfView(52f, 60f)))
        // An ultrawide is nowhere near it.
        assertFalse(matchesReferenceFieldOfView(FieldOfView(95f, 115f)))
        // Too narrow to keep the thirty six degree bearings overlapping.
        assertFalse(matchesReferenceFieldOfView(FieldOfView(38f, 63f)))
    }
}
