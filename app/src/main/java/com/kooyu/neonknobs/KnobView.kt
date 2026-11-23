package com.kooyu.neonknobs

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gros potard néon.
 * Tourner le doigt autour = valeur · tap = nom/CC · double tap potard = couleur · double tap nom = renommer.
 */
@SuppressLint("ViewConstructor")
class KnobView(context: Context, initialColor: Int) : View(context) {

    companion object {
        /** "Éteint" : gris sombre, le potard a l'air coupé. */
        const val ETEINT = 0xFF3A3A42.toInt()

        /** Rose, cyan, jaune, vert, orange, rouge, bleu, violet, éteint (ordre du double tap). */
        val NEON = intArrayOf(
            0xFFFF2E97.toInt(), 0xFF00F0FF.toInt(), 0xFFFFF01F.toInt(), 0xFF39FF14.toInt(),
            0xFFFF8C00.toInt(), 0xFFFF073A.toInt(), 0xFF1F51FF.toInt(), 0xFFBC13FE.toInt(), ETEINT,
        )
    }

    var name = ""
        set(v) { field = v; invalidate() }
    var cc = 0
    var value = 0
        set(v) { field = v.coerceIn(0, 127); invalidate() }
    var color = initialColor
        set(v) { field = v; invalidate() }
    /** false = style néon, true = style réaliste (potard métal + insert néon). */
    var realistic = false
        set(v) { field = v; invalidate() }

    var onValueChange: ((KnobView) -> Unit)? = null
    var onEdit: ((KnobView) -> Unit)? = null
    var onRename: ((KnobView) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val arc = RectF()
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var nameTop = 0f
    private var rainbow = -1f // progression 0..1 de l'anim arc-en-ciel, -1 = éteinte

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A0B2E") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val neonFont = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val realFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // setShadowLayer sur les arcs exige le rendu logiciel
        isClickable = true
    }

    /** Fait défiler l'arc-en-ciel pendant 1 s. */
    fun playRainbow() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            addUpdateListener { rainbow = it.animatedValue as Float; invalidate() }
            doOnEnd { rainbow = -1f; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val c = if (rainbow < 0) color else Color.HSVToColor(floatArrayOf(rainbow * 720f % 360f, 1f, 1f))
        if (realistic) return drawRealistic(canvas, c)
        text.typeface = neonFont
        track.color = Color.argb(60, Color.red(c), Color.green(c), Color.blue(c))
        glow.color = c
        glow.setShadowLayer(12 * dp, 0f, 0f, c)
        text.color = if (rainbow < 0) Color.WHITE else c
        text.setShadowLayer(8 * dp, 0f, 0f, c)

        val textSize = layout()
        val stroke = r * 0.16f
        track.strokeWidth = stroke
        glow.strokeWidth = stroke

        canvas.drawText(value.toString(), cx, textSize * 1.1f, text)

        arc.set(cx - r + stroke, cy - r + stroke, cx + r - stroke, cy + r - stroke)
        canvas.drawArc(arc, 135f, 270f, false, track)
        // pendant l'arc-en-ciel l'arc fait un tour complet, sinon il suit la valeur
        canvas.drawArc(arc, 135f, if (rainbow < 0) 270f * value / 127f else 270f * rainbow, false, glow)

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

    /** Place le potard dans la vue, renvoie la taille du texte. */
    private fun layout(): Float {
        val textSize = min(width, height) * 0.12f
        text.textSize = textSize
        cx = width / 2f
        r = min(width * 0.8f, height - textSize * 3.2f) / 2f
        cy = textSize * 1.6f + r
        nameTop = cy + r
        return textSize
    }

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** Potard façon matériel studio : graduations LED, jupe alu qui tourne, capuchon noir, insert néon lumineux. */
    private fun drawRealistic(canvas: Canvas, c: Int) {
        val textSize = layout()
        val deg = 135f + 270f * value / 127f
        val lit = if (rainbow < 0) value / 127f else rainbow

        // graduations : allumées en néon jusqu'à la valeur
        line.strokeWidth = r * 0.045f
        for (i in 0..20) {
            val f = i / 20f
            val a = Math.toRadians(135.0 + 270.0 * f)
            val on = f <= lit + 0.001f
            line.color = if (on) c else Color.argb(90, 200, 200, 210)
            if (on) line.setShadowLayer(6 * dp, 0f, 0f, c) else line.clearShadowLayer()
            canvas.drawLine(
                cx + (r * 0.86f * cos(a)).toFloat(), cy + (r * 0.86f * sin(a)).toFloat(),
                cx + (r * 0.98f * cos(a)).toFloat(), cy + (r * 0.98f * sin(a)).toFloat(), line
            )
        }
        line.clearShadowLayer()

        // ombre portée puis jupe en alu brossé (dégradé conique qui tourne avec le potard)
        val rs = r * 0.76f
        fill.shader = null
        fill.color = Color.BLACK
        fill.setShadowLayer(r * 0.14f, 0f, r * 0.08f, Color.argb(200, 0, 0, 0))
        canvas.drawCircle(cx, cy, rs, fill)
        fill.clearShadowLayer()
        canvas.save()
        canvas.rotate(deg, cx, cy)
        fill.shader = SweepGradient(cx, cy, intArrayOf(
            0xFFD9D9DC.toInt(), 0xFF6B6B70.toInt(), 0xFFF2F2F4.toInt(), 0xFF55555A.toInt(),
            0xFFD9D9DC.toInt(), 0xFF6B6B70.toInt(), 0xFFF2F2F4.toInt(), 0xFF55555A.toInt(), 0xFFD9D9DC.toInt(),
        ), null)
        canvas.drawCircle(cx, cy, rs, fill)
        // moletage
        line.strokeWidth = 1.2f * dp
        line.color = Color.argb(80, 0, 0, 0)
        for (i in 0 until 48) {
            val a = Math.toRadians(i * 7.5)
            canvas.drawLine(
                cx + (rs * 0.88f * cos(a)).toFloat(), cy + (rs * 0.88f * sin(a)).toFloat(),
                cx + (rs * cos(a)).toFloat(), cy + (rs * sin(a)).toFloat(), line
            )
        }
        canvas.restore()

        // capuchon noir laqué, lumière en haut à gauche
        val rc = rs * 0.74f
        fill.shader = RadialGradient(cx - rc * 0.35f, cy - rc * 0.35f, rc * 1.5f,
            0xFF4A4A50.toInt(), 0xFF0C0C0F.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rc, fill)

        // repère gravé noir sur la jupe
        val a = Math.toRadians(deg.toDouble())
        line.color = 0xFF111114.toInt()
        line.strokeWidth = r * 0.07f
        canvas.drawLine(
            cx + (rc * 1.04f * cos(a)).toFloat(), cy + (rc * 1.04f * sin(a)).toFloat(),
            cx + (rs * 0.95f * cos(a)).toFloat(), cy + (rs * 0.95f * sin(a)).toFloat(), line
        )

        // insert néon rétro-éclairé + reflet
        val rn = rc * 0.62f
        fill.shader = RadialGradient(cx, cy, rn, intArrayOf(mix(c, Color.WHITE, 0.55f), c, mix(c, Color.BLACK, 0.45f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        fill.setShadowLayer(r * 0.18f, 0f, 0f, c)
        canvas.drawCircle(cx, cy, rn, fill)
        fill.clearShadowLayer()
        fill.shader = RadialGradient(cx - rn * 0.3f, cy - rn * 0.45f, rn * 0.6f,
            Color.argb(110, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rn, fill)
        fill.shader = null

        // textes façon sérigraphie
        text.typeface = realFont
        text.color = if (c == ETEINT) Color.GRAY else c // sinon la valeur disparaît sur la façade sombre
        text.setShadowLayer(4 * dp, 0f, 0f, c)
        canvas.drawText(value.toString(), cx, textSize * 1.1f, text)
        text.color = if (rainbow < 0) 0xFFE6E6EA.toInt() else c
        text.setShadowLayer(2 * dp, 0f, 1 * dp, Color.BLACK)
        canvas.drawText(name, cx, cy + r + textSize * 1.3f, text)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent) = performClick()
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (e.y > nameTop) onRename?.invoke(this@KnobView)
            else color = NEON[(NEON.indexOf(color) + 1) % NEON.size]
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    private var downX = 0f
    private var downY = 0f
    private var lastAngle = 0f
    private var acc = 0f // valeur en flottant pour ne pas perdre les petits mouvements
    private var dragging = false

    private fun angle(e: MotionEvent) = Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastAngle = angle(e); acc = value.toFloat(); dragging = false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) dragging = true
                val a = angle(e)
                // près du centre l'angle part en vrille : on suit le doigt sans changer la valeur
                if (dragging && hypot(e.x - cx, e.y - cy) > r * 0.25f) {
                    var d = a - lastAngle
                    if (d > 180) d -= 360 else if (d < -180) d += 360
                    acc = (acc + d * 127f / 270f).coerceIn(0f, 127f) // 270° de course = 0→127, sens horaire = monte
                    val v = acc.roundToInt()
                    if (v != value) { value = v; onValueChange?.invoke(this) }
                }
                lastAngle = a
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onEdit?.invoke(this)
        return true
    }
}

private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = block()
})
