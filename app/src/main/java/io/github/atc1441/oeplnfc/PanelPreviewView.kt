package io.github.atc1441.oeplnfc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Shows the quantised result at the panel's own aspect ratio and lets the user
 * place the image inside it: drag to pan, pinch to zoom, two-finger twist to
 * rotate, double tap to reset.
 *
 * The gestures write straight into the shared [ImageOptions] and then ask for a
 * re-render, so what is on screen is always the exact pixels that would be
 * uploaded - not an approximation of them.
 */
class PanelPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var options: ImageOptions? = null
    var onChanged: (() -> Unit)? = null

    private var preview: Bitmap? = null
    private var panelW = 0
    private var panelH = 0

    private val framePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.GRAY
    }
    private val bmpPaint = Paint()   // no filtering: show the real pixels
    private val emptyPaint = Paint().apply {
        color = Color.GRAY
        textSize = 32f
        isAntiAlias = true
    }
    private val dst = Rect()

    // Kept rather than made each time: onDraw runs for every sample of a
    // drag, and an allocation there is an allocation at sixty hertz.
    private val frame = RectF()

    /** Scale from panel pixels to view pixels, for turning drags into offsets. */
    private var dispScale = 1f

    fun setPreview(bitmap: Bitmap?, w: Int, h: Int) {
        preview = bitmap
        panelW = w
        panelH = h
        invalidate()
    }

    // --- gestures -----------------------------------------------------------

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val o = options ?: return false
                o.scale = (o.scale * detector.scaleFactor).coerceIn(0.05f, 20f)
                onChanged?.invoke()
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                val o = options ?: return false
                // While a pinch is running the two fingers' midpoint wanders,
                // which the gesture detector reports as a scroll. Panning on
                // that makes the image slide around under the zoom, so during
                // a scale gesture the pan is off.
                if (scaleDetector.isInProgress) return true
                // A drag of one view pixel must move the image by one panel
                // pixel on screen, so divide by the display scale.
                if (dispScale > 0f) {
                    o.offsetX -= dx / dispScale
                    o.offsetY -= dy / dispScale
                    onChanged?.invoke()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                options?.resetTransform()
                onChanged?.invoke()
                return true
            }
        }
    )

    // Two-finger twist, tracked by hand: ScaleGestureDetector does not report
    // rotation and the alternative is a slider for something that wants a
    // direct gesture.
    private var lastAngle = Float.NaN

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // This view sits in a ScrollView, which otherwise claims any drag once
        // it passes the touch slop - so a pinch or a pan would scroll the page
        // instead of reaching the image. Take the gesture for the whole time a
        // finger is down and hand it back on the way up.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }

        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        if (event.pointerCount == 2) {
            val angle = Math.toDegrees(
                Math.atan2(
                    (event.getY(1) - event.getY(0)).toDouble(),
                    (event.getX(1) - event.getX(0)).toDouble()
                )
            ).toFloat()
            if (!lastAngle.isNaN()) {
                val o = options
                if (o != null) {
                    var delta = angle - lastAngle
                    while (delta > 180f) delta -= 360f
                    while (delta < -180f) delta += 360f
                    o.rotationDeg = (o.rotationDeg + delta + 360f) % 360f
                    onChanged?.invoke()
                }
            }
            lastAngle = angle
        } else {
            lastAngle = Float.NaN
        }
        return true
    }

    // --- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = preview
        if (b == null || panelW <= 0 || panelH <= 0) {
            canvas.drawText("No image", 16f, height / 2f, emptyPaint)
            return
        }

        // Letterbox the panel inside the view, keeping its aspect ratio.
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        dispScale = minOf(vw / panelW, vh / panelH)
        val dw = panelW * dispScale
        val dh = panelH * dispScale
        val left = paddingLeft + (vw - dw) / 2f
        val top = paddingTop + (vh - dh) / 2f

        dst.set(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
        canvas.drawBitmap(b, null, dst, bmpPaint)
        frame.set(left, top, left + dw, top + dh)
        canvas.drawRect(frame, framePaint)
    }
}
