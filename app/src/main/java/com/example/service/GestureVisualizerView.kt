package com.example.service

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class GestureVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        setWillNotDraw(false)
        visibility = View.VISIBLE
        // Set an almost entirely transparent black background (alpha 1/255) to force the system compositor to keep the view active and process drawing invalidations
        setBackgroundColor(Color.argb(1, 0, 0, 0))
        android.util.Log.d("Zhypix", "GestureVisualizerView initialized with setWillNotDraw(false), visibility=VISIBLE and near-transparent compositor background")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        android.util.Log.d("Zhypix", "GestureVisualizerView size changed: width = $w, height = $h")
    }

    private val clickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.BLACK // Premium Black click ring
    }
    
    private val clickFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4D000000") // Translucent Black click fill
    }

    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK // Premium Black swipe path
    }

    private val swipeHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK // Premium Black swipe head nodes
    }

    // click states
    private var clickX = 0f
    private var clickY = 0f
    private var clickRadius = 0f
    private var clickAlpha = 0

    // swipe states
    private var isSwiping = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeEndX = 0f
    private var swipeEndY = 0f
    private var swipeCurrentX = 0f
    private var swipeCurrentY = 0f
    private var swipeLineAlpha = 0
    private var swipeHeadAlpha = 0

    // screenshot light grey light state
    private val greenLedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#71717A") // Subtle Soft Grey LED
    }

    private val greenGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4D71717A") // Translucent Grey Glow
    }

    private val greenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#666666") // Soft Light Black / Grey Frame Border
    }

    private val greenTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4D4D8")
        textSize = 30f
        isFakeBoldText = true
    }

    private val greenTextBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#DD000000") // Dark translucent pill bg
    }

    private var screenshotAlpha = 0
    private var screenshotGlowRadius = 24f

    fun showScreenshotFlash() {
        android.util.Log.d("Zhypix", "showScreenshotFlash called on visualizer (Green LED light indicator)")
        
        val alphaAnimator = ValueAnimator.ofInt(255, 0).apply {
            duration = 1200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                screenshotAlpha = animator.animatedValue as Int
                invalidate()
                postInvalidate()
            }
        }

        val glowAnimator = ValueAnimator.ofFloat(20f, 45f).apply {
            duration = 600
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                screenshotGlowRadius = animator.animatedValue as Float
                invalidate()
                postInvalidate()
            }
        }

        val runAnim = {
            AnimatorSet().apply {
                playTogether(alphaAnimator, glowAnimator)
                start()
            }
            android.util.Log.d("Zhypix", "Screenshot green light animation started")
            Unit
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runAnim()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(runAnim)
        }
    }

    fun showClickFeedback(x: Float, y: Float) {
        android.util.Log.d("Zhypix", "showClickFeedback called on visualizer at ($x, $y)")
        clickX = x
        clickY = y
        
        val radiusAnimator = ValueAnimator.ofFloat(15f, 250f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                clickRadius = animator.animatedValue as Float
                invalidate()
                postInvalidate()
            }
        }

        val alphaAnimator = ValueAnimator.ofInt(255, 0).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                clickAlpha = animator.animatedValue as Int
                invalidate()
                postInvalidate()
            }
        }

        val runAnim = {
            AnimatorSet().apply {
                playTogether(radiusAnimator, alphaAnimator)
                start()
            }
            android.util.Log.d("Zhypix", "Click feedback animator started")
            Unit
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runAnim()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(runAnim)
        }
    }

    fun showSwipeFeedback(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        android.util.Log.d("Zhypix", "showSwipeFeedback called on visualizer: ($startX, $startY) -> ($endX, $endY) over ${durationMs}ms")
        swipeStartX = startX
        swipeStartY = startY
        swipeEndX = endX
        swipeEndY = endY
        swipeCurrentX = startX
        swipeCurrentY = startY
        isSwiping = true
        swipeLineAlpha = 200
        swipeHeadAlpha = 255

        val actionDuration = if (durationMs < 100L) 100L else durationMs

        // Animate progression of brush line
        val pathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = actionDuration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                swipeCurrentX = swipeStartX + (swipeEndX - swipeStartX) * fraction
                swipeCurrentY = swipeStartY + (swipeEndY - swipeStartY) * fraction
                invalidate()
                postInvalidate()
            }
        }

        // Fade trace and head points
        val fadeAnimator = ValueAnimator.ofInt(200, 0).apply {
            duration = 400
            startDelay = actionDuration
            addUpdateListener { animator ->
                val v = animator.animatedValue as Int
                swipeLineAlpha = v
                swipeHeadAlpha = (v * (255f / 200f)).toInt()
                invalidate()
                postInvalidate()
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    isSwiping = false
                    invalidate()
                    postInvalidate()
                }
            })
        }

        val runAnim = {
            AnimatorSet().apply {
                playSequentially(pathAnimator, fadeAnimator)
                start()
            }
            android.util.Log.d("Zhypix", "Swipe feedback animator started")
            Unit
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runAnim()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(runAnim)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        android.util.Log.v("Zhypix", "onDraw executed: width=$width, height=$height, clickAlpha=$clickAlpha, isSwiping=$isSwiping, clickX=$clickX, clickY=$clickY")

        // Render Click
        if (clickAlpha > 0) {
            clickPaint.alpha = clickAlpha
            clickFillPaint.alpha = (clickAlpha * 0.25f).toInt()
            
            // Pulsing target ring
            canvas.drawCircle(clickX, clickY, clickRadius, clickPaint)
            // Translucent glowing center indicator
            canvas.drawCircle(clickX, clickY, clickRadius * 0.4f, clickFillPaint)
            
            // Reticle hair pins
            val hairLength = 20f
            val crossPaint = Paint(clickPaint).apply { strokeWidth = 4f }
            canvas.drawLine(clickX - hairLength, clickY, clickX + hairLength, clickY, crossPaint)
            canvas.drawLine(clickX, clickY - hairLength, clickX, clickY + hairLength, crossPaint)
        }

        // Render Swipe path and indicator dots
        if (isSwiping) {
            swipePaint.alpha = swipeLineAlpha
            canvas.drawLine(swipeStartX, swipeStartY, swipeCurrentX, swipeCurrentY, swipePaint)
            
            swipeHeadPaint.alpha = swipeHeadAlpha
            // Small anchor start node
            canvas.drawCircle(swipeStartX, swipeStartY, 18f, swipeHeadPaint)
            // Fast leading node
            canvas.drawCircle(swipeCurrentX, swipeCurrentY, 26f, swipeHeadPaint)
        }

        // Render Green Screenshot LED Indicator Light & Frame Flash
        if (screenshotAlpha > 0) {
            val cx = width - 70f
            val cy = 110f

            // 1. Screen Edge Green Border Flash
            greenBorderPaint.alpha = (screenshotAlpha * 0.7f).toInt()
            canvas.drawRect(6f, 6f, width - 6f, height - 6f, greenBorderPaint)

            // 2. Dark translucent pill background badge
            greenTextBgPaint.alpha = (screenshotAlpha * 0.85f).toInt()
            val badgeRight = cx + 32f
            val badgeLeft = cx - 260f
            val badgeTop = cy - 32f
            val badgeBottom = cy + 32f
            val rx = 32f
            canvas.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, rx, rx, greenTextBgPaint)

            // 3. Translucent Glowing Green Aura
            greenGlowPaint.alpha = (screenshotAlpha * 0.6f).toInt()
            canvas.drawCircle(cx, cy, screenshotGlowRadius, greenGlowPaint)

            // 4. Solid Vibrant Green LED Dot
            greenLedPaint.alpha = screenshotAlpha
            canvas.drawCircle(cx, cy, 14f, greenLedPaint)

            // 5. White Core Center Dot
            val whiteCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = screenshotAlpha
            }
            canvas.drawCircle(cx, cy, 5f, whiteCorePaint)

            // 6. Text Label
            greenTextPaint.alpha = screenshotAlpha
            canvas.drawText("AI Vision Active", badgeLeft + 24f, cy + 10f, greenTextPaint)
        }
    }
}
