package com.n30dyn4m1c.photosphere.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitPlanTest {

    @Test
    fun twelveAnglesStartAtZero() {
        val plan = orbitPlan(12, 0f)
        assertEquals(12, plan.angles.size)
        assertEquals(0f, plan.angles[0], 0.001f)
        assertEquals(90f, plan.angles[3], 0.001f)
        assertEquals(-90f, plan.angles[9], 0.001f)
    }

    @Test
    fun nextTargetSkipsCaptured() {
        val plan = orbitPlan(4, 0f)
        assertEquals(0, plan.nextTarget(emptySet()))
        assertEquals(2, plan.nextTarget(setOf(0, 1)))
        assertNull(plan.nextTarget(setOf(0, 1, 2, 3)))
    }

    @Test
    fun onTargetUsesTolerance() {
        val plan = OrbitPlan(listOf(90f), toleranceDegrees = 6f)
        assertTrue(plan.isOnTarget(94f, 0))
        assertFalse(plan.isOnTarget(100f, 0))
    }

    @Test
    fun turnToTakesShortestWay() {
        val plan = OrbitPlan(listOf(-179f))
        assertEquals(1f, plan.turnTo(180f, 0), 0.001f)
    }

    @Test
    fun wrapAngleStaysInRange() {
        assertEquals(-179f, wrapAngle(181f), 0.001f)
        assertEquals(179f, wrapAngle(-181f), 0.001f)
        assertEquals(0f, wrapAngle(360f), 0.001f)
    }
}
