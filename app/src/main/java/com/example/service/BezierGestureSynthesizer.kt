package com.example.service

import android.graphics.Path
import android.graphics.Rect
import java.util.Random

/**
 * Human-like Gesture Motion Synthesizer utilizing Cubic Bezier curves,
 * velocity profiling, Gaussian coordinate jitter, and realistic log-normal dwell times.
 */
object BezierGestureSynthesizer {

    private val random = Random()

    /**
     * Generates a cubic Bezier curve Path between start (sx, sy) and end (ex, ey)
     * with organic control point offsets.
     */
    fun createHumanSwipePath(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Path {
        val path = Path()
        path.moveTo(startX, startY)

        val dx = endX - startX
        val dy = endY - startY
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (distance < 10f) {
            path.lineTo(endX, endY)
            return path
        }

        // Perpendicular offset for organic curvature (10-25% of distance)
        val curvature = (0.10f + random.nextFloat() * 0.15f) * distance
        val side = if (random.nextBoolean()) 1f else -1f

        // Unit normal vector
        val nx = -dy / distance * side
        val ny = dx / distance * side

        // Control points at 25% and 75% along segment with perpendicular displacement
        val cp1x = startX + dx * 0.25f + nx * curvature
        val cp1y = startY + dy * 0.25f + ny * curvature

        val cp2x = startX + dx * 0.75f + nx * (curvature * 0.7f)
        val cp2y = startY + dy * 0.75f + ny * (curvature * 0.7f)

        path.cubicTo(cp1x, cp1y, cp2x, cp2y, endX, endY)
        return path
    }

    /**
     * Calculates a human-like swipe duration in milliseconds (log-normal distribution).
     */
    fun calculateHumanSwipeDuration(distancePx: Float): Long {
        val baseMs = 200L + (distancePx * 0.15f).coerceAtMost(300f).toLong()
        val jitterMs = (random.nextGaussian() * 35).toLong()
        return (baseMs + jitterMs).coerceIn(180L, 650L)
    }

    /**
     * Adds Gaussian spatial jitter to a click target within element bounds or ±4px.
     */
    fun applyHumanClickJitter(targetX: Float, targetY: Float, bounds: Rect? = null): Pair<Float, Float> {
        val stdDev = 3.5f
        var jx = (random.nextGaussian() * stdDev).toFloat()
        var jy = (random.nextGaussian() * stdDev).toFloat()

        var finalX = targetX + jx
        var finalY = targetY + jy

        if (bounds != null && bounds.width() > 10 && bounds.height() > 10) {
            // Constrain jitter within 80% inner rect of the target element
            val marginX = (bounds.width() * 0.10f).coerceAtLeast(2f)
            val marginY = (bounds.height() * 0.10f).coerceAtLeast(2f)

            finalX = finalX.coerceIn(bounds.left + marginX, bounds.right - marginX)
            finalY = finalY.coerceIn(bounds.top + marginY, bounds.bottom - marginY)
        }

        return Pair(finalX, finalY)
    }

    /**
     * Calculates log-normal human reading hesitation time based on text word count on screen.
     */
    fun calculateReadingTimeMs(wordCount: Int): Long {
        if (wordCount <= 5) return 100L
        // Average reading speed ~200 words/min = ~300ms per word
        val baseReadingTime = (wordCount * 120L).coerceAtMost(1200L)
        val gaussianJitter = (random.nextGaussian() * 150).toLong()
        return (baseReadingTime + gaussianJitter).coerceIn(120L, 1500L)
    }
}
