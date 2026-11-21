package com.kooyu.neonknobs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Gros potard néon : glisser haut/bas = valeur, tap = édition nom/CC. */
@SuppressLint("ViewConstructor")
class KnobView(context: Context, private val neon: Int) : View(context) {

    var name = ""
        set(v) { field = v; invalidate() }
    var cc = 0
    var value = 0
        set(v) { field = v.coerceIn(0, 127); invalidate() }

    var onValueChange: ((KnobView) -> Unit)? = null
    var onEdit: ((KnobView) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val arc = RectF()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = Color.argb(60, Color.red(neon), Color.green(neon), Color.blue(neon))
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = neon
        setShadowLayer(12 * dp, 0f, 0f, neon)
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A0B2E") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(8 * dp, 0f, 0f, neon)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // setShadowLayer sur les arcs exige le rendu logiciel
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val textSize = min(width, height) * 0.12f
        text.textSize = textSize
        val cx = width / 2f
        val r = min(width * 0.8f, height - textSize * 3.2f) / 2f
        val cy = textSize * 1.6f + r
        val stroke = r * 0.16f
        track.strokeWidth = stroke
        glow.strokeWidth = stroke

        canvas.drawText(value.toString(), cx, textSize * 1.1f, text)

        arc.set(cx - r + stroke, cy - r + stroke, cx + r - stroke, cy + r - stroke)
        canvas.drawArc(arc, 135f, 270f, false, track)
        canvas.drawArc(arc, 135f, 270f * value / 127f, false, glow)

        val inner = r - stroke * 2f
        canvas.drawCircle(cx, cy, inner, body)
        val a = Math.toRadians(135.0 + 270.0 * value / 127.0)
        canvas.drawLine(
            cx, cy,
            cx + (inner * 0.85f * cos(a)).toFloat(), cy + (inner * 0.85f * sin(a)).toFloat(),
            glow
        )

        canvas.drawText(name, cx, cy + r + textSize * 1.3f, text)
    }

    private var downY = 0f
    private var startValue = 0
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; startValue = value; dragging = false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = downY - e.y
                if (!dragging && abs(dy) > slop) dragging = true
                if (dragging) {
                    // course complète 0→127 sur ~1,5x la hauteur du potard
                    val v = (startValue + dy * 127f / (height * 1.5f)).toInt().coerceIn(0, 127)
                    if (v != value) { value = v; onValueChange?.invoke(this) }
                }
            }
            MotionEvent.ACTION_UP -> if (!dragging) performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onEdit?.invoke(this)
        return true
    }
}
