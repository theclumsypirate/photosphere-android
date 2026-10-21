package com.n30dyn4m1c.photosphere.orbit

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Optical flow odometry for orbit capture.
 *
 * Tracks background feature points between consecutive grayscale frames and
 * reports the median horizontal pixel shift. The caller converts pixels to
 * angle with the camera intrinsics. Person pixels are excluded via an
 * optional mask so the subject, which stays centered, does not bias motion.
 *
 * All inputs are borrowed and never released. All Mats allocated inside
 * [track] are released before return.
 */
class OrbitFlowOdometry {

    /**
     * Minimum number of successfully tracked points needed for a valid reading.
     *
     * Below this count the median is noise, so [track] returns 0 instead.
     */
    var minTrackedPoints: Int = 10

    /**
     * Maximum number of corners requested from the detector per frame.
     */
    var maxCorners: Int = 200

    /**
     * Reset any accumulated state.
     *
     * This implementation is stateless between frame pairs, so reset is a
     * no-op kept for callers that reuse one instance across captures.
     */
    fun reset() {
        // No state to clear. Method exists for API symmetry.
    }

    /**
     * Track background motion from [prevGray] to [currGray].
     *
     * @param prevGray previous grayscale frame (CV_8U, single channel).
     * @param currGray current grayscale frame (same size and type as [prevGray]).
     * @param personMask optional mask (CV_8U, 255 means person, may be null).
     * Pixels marked as person are never used as feature points.
     * @return median horizontal shift in pixels (curr.x minus prev.x), or 0
     * when fewer than [minTrackedPoints] points track successfully.
     */
    fun track(prevGray: Mat, currGray: Mat, personMask: Mat?): Float {
        val corners = MatOfPoint()
        val prevPts = MatOfPoint2f()
        val nextPts = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        val bgMask = Mat()
        try {
            if (personMask != null) {
                Imgproc.threshold(personMask, bgMask, 1.0, 255.0, Imgproc.THRESH_BINARY_INV)
            }
            val maskArg: Mat? = if (personMask != null) bgMask else null
            Imgproc.goodFeaturesToTrack(
                prevGray,
                corners,
                maxCorners,
                0.01,
                10.0,
                maskArg,
            )
            if (corners.empty()) return 0f
            corners.convertTo(prevPts, CvType.CV_32F)
            Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, nextPts, status, err)
            val statusArr = status.toArray()
            val prevArr = prevPts.toArray()
            val nextArr = nextPts.toArray()
            val count = minOf(statusArr.size, prevArr.size, nextArr.size)
            val shifts = ArrayList<Float>(count)
            for (i in 0 until count) {
                if (statusArr[i].toInt() != 0) {
                    shifts.add((nextArr[i].x - prevArr[i].x).toFloat())
                }
            }
            if (shifts.size < minTrackedPoints) return 0f
            shifts.sort()
            val mid = shifts.size / 2
            return if (shifts.size % 2 == 1) {
                shifts[mid]
            } else {
                (shifts[mid - 1] + shifts[mid]) / 2f
            }
        } finally {
            corners.release()
            prevPts.release()
            nextPts.release()
            status.release()
            err.release()
            bgMask.release()
        }
    }
}
