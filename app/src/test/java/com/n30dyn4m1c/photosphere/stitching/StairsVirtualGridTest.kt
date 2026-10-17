package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Virtual capture grid cut from tools/pano_proto/viewer/stairs_360.jpg.
 *
 * The internet pano ships no capture coords, so the grid was defined here:
 * five pitch rows (-45, -20, 0, 20, 45) with ten yaw steps of 36 degrees,
 * plus six +75 zenith caps (0, 60, 120, 180, -120, -60) and six -75 nadir
 * caps (same yaws). The Python side
 * (tools/pano_proto/virtual_frames_stairs.py) ray-casts one portrait frame
 * per entry through the exact pivot camera model the stitcher inverts
 * (H_FOV 49, V_FOV 63, lever 0.35 m, scene 3.5 m), stamps yaw/pitch/roll in
 * EXIF UserComment, and stitch_closed.py stitches the set back.
 *
 * Test-only file: it never ships in the app and never touches the normal
 * camera path. It locks the grid so a later Python regeneration that drops
 * coverage fails loudly here instead of silently.
 */
data class VirtualFrame(val file: String, val yaw: Double, val pitch: Double)

private val STAIRS_GRID = listOf(
    VirtualFrame("vframe_01.jpg", -180.0, -45.0),
    VirtualFrame("vframe_02.jpg", -144.0, -45.0),
    VirtualFrame("vframe_03.jpg", -108.0, -45.0),
    VirtualFrame("vframe_04.jpg", -72.0, -45.0),
    VirtualFrame("vframe_05.jpg", -36.0, -45.0),
    VirtualFrame("vframe_06.jpg", 0.0, -45.0),
    VirtualFrame("vframe_07.jpg", 36.0, -45.0),
    VirtualFrame("vframe_08.jpg", 72.0, -45.0),
    VirtualFrame("vframe_09.jpg", 108.0, -45.0),
    VirtualFrame("vframe_10.jpg", 144.0, -45.0),
    VirtualFrame("vframe_11.jpg", -180.0, -20.0),
    VirtualFrame("vframe_12.jpg", -144.0, -20.0),
    VirtualFrame("vframe_13.jpg", -108.0, -20.0),
    VirtualFrame("vframe_14.jpg", -72.0, -20.0),
    VirtualFrame("vframe_15.jpg", -36.0, -20.0),
    VirtualFrame("vframe_16.jpg", 0.0, -20.0),
    VirtualFrame("vframe_17.jpg", 36.0, -20.0),
    VirtualFrame("vframe_18.jpg", 72.0, -20.0),
    VirtualFrame("vframe_19.jpg", 108.0, -20.0),
    VirtualFrame("vframe_20.jpg", 144.0, -20.0),
    VirtualFrame("vframe_21.jpg", -180.0, 0.0),
    VirtualFrame("vframe_22.jpg", -144.0, 0.0),
    VirtualFrame("vframe_23.jpg", -108.0, 0.0),
    VirtualFrame("vframe_24.jpg", -72.0, 0.0),
    VirtualFrame("vframe_25.jpg", -36.0, 0.0),
    VirtualFrame("vframe_26.jpg", 0.0, 0.0),
    VirtualFrame("vframe_27.jpg", 36.0, 0.0),
    VirtualFrame("vframe_28.jpg", 72.0, 0.0),
    VirtualFrame("vframe_29.jpg", 108.0, 0.0),
    VirtualFrame("vframe_30.jpg", 144.0, 0.0),
    VirtualFrame("vframe_31.jpg", -180.0, 20.0),
    VirtualFrame("vframe_32.jpg", -144.0, 20.0),
    VirtualFrame("vframe_33.jpg", -108.0, 20.0),
    VirtualFrame("vframe_34.jpg", -72.0, 20.0),
    VirtualFrame("vframe_35.jpg", -36.0, 20.0),
    VirtualFrame("vframe_36.jpg", 0.0, 20.0),
    VirtualFrame("vframe_37.jpg", 36.0, 20.0),
    VirtualFrame("vframe_38.jpg", 72.0, 20.0),
    VirtualFrame("vframe_39.jpg", 108.0, 20.0),
    VirtualFrame("vframe_40.jpg", 144.0, 20.0),
    VirtualFrame("vframe_41.jpg", -180.0, 45.0),
    VirtualFrame("vframe_42.jpg", -144.0, 45.0),
    VirtualFrame("vframe_43.jpg", -108.0, 45.0),
    VirtualFrame("vframe_44.jpg", -72.0, 45.0),
    VirtualFrame("vframe_45.jpg", -36.0, 45.0),
    VirtualFrame("vframe_46.jpg", 0.0, 45.0),
    VirtualFrame("vframe_47.jpg", 36.0, 45.0),
    VirtualFrame("vframe_48.jpg", 72.0, 45.0),
    VirtualFrame("vframe_49.jpg", 108.0, 45.0),
    VirtualFrame("vframe_50.jpg", 144.0, 45.0),
    VirtualFrame("vframe_51.jpg", 0.0, 75.0),
    VirtualFrame("vframe_52.jpg", 60.0, 75.0),
    VirtualFrame("vframe_53.jpg", 120.0, 75.0),
    VirtualFrame("vframe_54.jpg", 180.0, 75.0),
    VirtualFrame("vframe_55.jpg", -120.0, 75.0),
    VirtualFrame("vframe_56.jpg", -60.0, 75.0),
    VirtualFrame("vframe_57.jpg", 0.0, -75.0),
    VirtualFrame("vframe_58.jpg", 60.0, -75.0),
    VirtualFrame("vframe_59.jpg", 120.0, -75.0),
    VirtualFrame("vframe_60.jpg", 180.0, -75.0),
    VirtualFrame("vframe_61.jpg", -120.0, -75.0),
    VirtualFrame("vframe_62.jpg", -60.0, -75.0)
)

private const val H_FOV = 49.0
private const val GRID_SIZE = 62

class StairsVirtualGridTest {

    @Test
    fun gridHasExpectedFrameCount() {
        assertEquals(GRID_SIZE, STAIRS_GRID.size)
    }

    @Test
    fun noDuplicateFilesOrPoses() {
        assertEquals(STAIRS_GRID.size, STAIRS_GRID.map { it.file }.toSet().size)
        val poses = STAIRS_GRID.map { it.yaw to it.pitch }.toSet()
        assertEquals(STAIRS_GRID.size, poses.size)
    }

    @Test
    fun everyPitchRowCoversFullYawRingWithOverlap() {
        val rows = STAIRS_GRID.filter { abs(it.pitch) <= 45.0 }.groupBy { it.pitch }
        assertEquals(5, rows.size)
        for ((pitch, frames) in rows) {
            val yaws = frames.map { it.yaw }.sorted()
            assertEquals("row pitch=$pitch", 10, yaws.size)
            for (i in yaws.indices) {
                val next = yaws[(i + 1) % yaws.size]
                var step = next - yaws[i]
                if (step <= 0) step += 360.0
                assertTrue(
                    "row pitch=$pitch yaw step $step leaves a gap (H_FOV=$H_FOV)",
                    step < H_FOV
                )
            }
        }
    }

    @Test
    fun poleCapsExist() {
        val caps = STAIRS_GRID.filter { abs(it.pitch) > 60.0 }
        assertEquals("zenith caps", 6, caps.count { it.pitch == 75.0 })
        assertEquals("nadir caps", 6, caps.count { it.pitch == -75.0 })
    }

    @Test
    fun gridMatchesCoordsJson() {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("virtual_stairs_coords.json")
            ?: return
        val text = stream.bufferedReader().readText()
        for (frame in STAIRS_GRID) {
            assertTrue(
                "${frame.file} yaw=${frame.yaw} pitch=${frame.pitch} not in coords.json",
                text.contains("\"file\": \"${frame.file}\"")
            )
        }
    }
}
