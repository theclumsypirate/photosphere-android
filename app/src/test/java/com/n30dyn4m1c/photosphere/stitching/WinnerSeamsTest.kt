package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Test

class WinnerSeamsTest {

    @Test
    fun `nothing covering means no owner`() {
        assertEquals(-1, SeamFinder.nearestCenter(intArrayOf(2, 5), doubleArrayOf(1.0, 2.0), 0))
    }

    @Test
    fun `a lone covering frame owns the node`() {
        assertEquals(4, SeamFinder.nearestCenter(intArrayOf(4), doubleArrayOf(900.0), 1))
    }

    @Test
    fun `the closest-to-centre frame wins regardless of order`() {
        assertEquals(
            5,
            SeamFinder.nearestCenter(
                intArrayOf(2, 5, 3),
                doubleArrayOf(400.0, 12.0, 300.0),
                3,
            ),
        )
    }

    @Test
    fun `ties keep the first frame`() {
        assertEquals(
            2,
            SeamFinder.nearestCenter(intArrayOf(2, 5), doubleArrayOf(7.0, 7.0), 2),
        )
    }

    @Test
    fun `entries past the count are ignored`() {
        assertEquals(
            5,
            SeamFinder.nearestCenter(intArrayOf(2, 5, 9), doubleArrayOf(400.0, 12.0, 0.0), 2),
        )
    }
}
