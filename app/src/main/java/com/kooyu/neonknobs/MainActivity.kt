package com.kooyu.neonknobs

import android.app.Activity
import android.app.AlertDialog
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity() {

    private val knobs = mutableListOf<KnobView>()
    private lateinit var status: TextView
    private lateinit var configName: TextView
    private lateinit var wave: WaveOverlay
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private val main = Handler(Looper.getMainLooper())
    private var midiManager: MidiManager? = null
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SynthwaveBackground()
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p)
        }
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            textSize = 14f
        }
        root.addView(status)

        for (row in 0..1) {
            if (row == 1) root.addView(configBar())
            val line = LinearLayout(this)
            for (col in 0..5) {
                val i = row * 6 + col
                val knob = KnobView(this, prefs.getInt("col$i", KnobView.NEON[row])).apply {
                    name = prefs.getString("name$i", null) ?: "Potard ${i + 1}"
                    cc = prefs.getInt("cc$i", 20 + i) // Potard 1 = CC 20 … Potard 12 = CC 31
                    value = prefs.getInt("val$i", 0)
                    onValueChange = { sendCc(it.cc, it.value) }
                    onEdit = { editKnob(it, withCc = true) }
                    onRename = { editKnob(it, withCc = false) }
                }
                knobs += knob
                line.addView(knob, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        wave = WaveOverlay(this, root)
        setContentView(FrameLayout(this).apply { addView(root); addView(wave) })
        if (configs().length() == 0) saveConfig(currentConfig(), animate = false) // 1er lancement : l'état actuel devient "Défaut"
        updateStatus()
        startMidi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION") // marche de l'API 23 à 34 sans double code
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // onPause est toujours appelé avant que l'appli soit fermée/tuée
    override fun onPause() {
        super.onPause()
        prefs.edit().apply {
            knobs.forEachIndexed { i, k ->
                putString("name$i", k.name); putInt("cc$i", k.cc); putInt("val$i", k.value); putInt("col$i", k.color)
            }
        }.apply()
    }

    override fun onDestroy() {
        midiManager?.unregisterDeviceCallback(deviceCallback)
        closeMidi()
        super.onDestroy()
    }

    // ---------- Réglages ----------

    private fun editKnob(k: KnobView, withCc: Boolean) {
        val nameField = EditText(this).apply { setText(k.name); hint = "Nom"; setSingleLine(); selectAll() }
        val ccField = EditText(this).apply {
            setText(k.cc.toString()); hint = "CC (0-127)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(nameField); if (withCc) addView(ccField)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Potard ${knobs.indexOf(k) + 1}")
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                val cc = ccField.text.toString().toIntOrNull()
                if (cc == null || cc !in 0..127) {
                    Toast.makeText(this, "CC invalide : 0 à 127", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                k.name = nameField.text.toString().trim().ifEmpty { k.name }
                k.cc = cc
                k.playRainbow()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- Configurations ----------
    // Stockées en JSON dans les prefs : { "nom": [ {n, cc, c, v} x12 ] }

    private fun configs() = JSONObject(prefs.getString("configs", null) ?: "{}")
    private fun currentConfig() = prefs.getString("current", null) ?: "Défaut"

    private fun configBar(): View {
        val d = resources.displayMetrics.density
        fun pill(t: String, neon: Int) = TextView(this).apply {
            text = t
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 16f
            setTextColor(Color.WHITE)
            setShadowLayer(8 * d, 0f, 0f, neon)
            setPadding((18 * d).toInt(), (6 * d).toInt(), (18 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A0B2E")); cornerRadius = 40 * d
            }
        }
        configName = pill("", KnobView.NEON[1]).apply { setOnClickListener { pickConfig(it) } }
        showConfigName()
        val save = pill("💾", KnobView.NEON[0]).apply { setOnClickListener { askSaveConfig() } }
        return RgbBar(this).apply {
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            addView(configName)
            addView(save, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (10 * d).toInt() })
        }
    }

    private fun showConfigName() { configName.text = "${currentConfig()}  ▼" }

    private fun pickConfig(anchor: View) {
        val names = configs().keys().asSequence().sortedBy { it.lowercase() }.toList()
        PopupMenu(this, anchor).apply {
            names.forEach { menu.add(it) }
            setOnMenuItemClickListener { loadConfig(it.title.toString()); true }
            show()
        }
    }

    private fun askSaveConfig() {
        val field = EditText(this).apply { setText(currentConfig()); hint = "Nom de la config"; setSingleLine(); selectAll() }
        val form = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(field)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Enregistrer la config")
            .setView(form)
            .setPositiveButton("Enregistrer") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) Toast.makeText(this, "Donne-lui un nom", Toast.LENGTH_SHORT).show()
                else saveConfig(name) // même nom = écrase
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun saveConfig(name: String, animate: Boolean = true) {
        val knobsJson = JSONArray()
        knobs.forEach { knobsJson.put(JSONObject().put("n", it.name).put("cc", it.cc).put("c", it.color).put("v", it.value)) }
        prefs.edit().putString("configs", configs().put(name, knobsJson).toString()).putString("current", name).apply()
        showConfigName()
        if (animate) wave.play()
    }

    // Les valeurs reviennent à l'écran sans être envoyées au PC, comme au démarrage de l'appli.
    private fun loadConfig(name: String) {
        val a = configs().optJSONArray(name) ?: return
        knobs.forEachIndexed { i, k ->
            val o = a.optJSONObject(i) ?: return@forEachIndexed
            k.name = o.optString("n", k.name); k.cc = o.optInt("cc", k.cc)
            k.color = o.optInt("c", k.color); k.value = o.optInt("v", k.value)
        }
        prefs.edit().putString("current", name).apply()
        showConfigName()
        wave.play()
    }

    // ---------- MIDI ----------
    // En mode USB "MIDI", Android expose au PC un port USB MIDI class-compliant
    // ("Android USB Peripheral Port") : rien à installer côté PC.

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = open(info)
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info == device?.info) closeMidi()
        }
    }

    private fun startMidi() {
        val mm = getSystemService(MIDI_SERVICE) as? MidiManager ?: return updateStatus("MIDI non supporté")
        midiManager = mm
        mm.registerDeviceCallback(deviceCallback, main)
        @Suppress("DEPRECATION") // getDevicesForTransport() n'existe qu'à partir de l'API 33
        mm.devices.forEach(::open)
    }

    private fun open(info: MidiDeviceInfo) {
        // shortcut: prend le 1er port USB trouvé ; si un clavier USB est branché en OTG en même temps, il pourrait être pris à la place
        if (device != null || opening || info.type != MidiDeviceInfo.TYPE_USB || info.inputPortCount == 0) return
        opening = true
        midiManager?.openDevice(info, { dev ->
            opening = false
            device = dev
            port = dev?.openInputPort(0)
            if (port == null) closeMidi() else updateStatus()
        }, main)
    }

    private fun closeMidi() {
        try { port?.close(); device?.close() } catch (_: IOException) {}
        port = null; device = null
        updateStatus()
    }

    private fun sendCc(cc: Int, value: Int) {
        val p = port ?: return
        try {
            p.send(byteArrayOf(0xB0.toByte(), cc.toByte(), value.toByte()), 0, 3) // 0xB0 = Control Change, canal 1
        } catch (_: IOException) {
            closeMidi()
        }
    }

    private fun updateStatus(error: String? = null) {
        val ok = port != null
        status.text = error ?: if (ok) "● USB MIDI connecté" else "○ USB MIDI déconnecté — branche le PC et choisis USB : MIDI"
        status.setTextColor(if (ok) Color.parseColor("#39FF14") else Color.parseColor("#FFB000"))
    }
}

/**
 * Vague invisible : une ondulation qui traverse l'écran de gauche à droite (enregistrement / chargement de config).
 * On photographie l'écran puis on redessine la bande de la vague en colonnes décalées, comme vue à travers de l'eau.
 */
private class WaveOverlay(context: android.content.Context, private val target: View) : View(context) {
    private var t = -1f
    private var shot: Bitmap? = null
    private val dp = resources.displayMetrics.density
    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun play() {
        if (target.width == 0 || shot != null) return
        shot = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { target.draw(Canvas(it)) }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            addUpdateListener {
                t = it.animatedValue as Float
                if (t >= 1f) { shot?.recycle(); shot = null }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = shot ?: return
        val w = bmp.width
        val h = bmp.height.toFloat()
        val band = w * 0.3f
        val front = -band + t * (w + 2 * band)
        val strip = (3 * dp).toInt().coerceAtLeast(1)
        val amp = 14 * dp
        var x = (front - band).toInt().coerceAtLeast(0)
        val end = (front + band).toInt().coerceAtMost(w)
        while (x < end) {
            val d = (x + strip / 2f - front) / band // -1..1 dans la bande
            val env = (1 - d * d).let { it * it }    // 0 aux bords, 1 au centre : pas de cassure
            val a = d * 3 * PI
            val sx = (x + amp * env * sin(a)).toInt().coerceIn(0, w - strip)
            val dy = amp * 0.5f * env * cos(a).toFloat()
            src.set(sx, 0, sx + strip, bmp.height)
            dst.set(x.toFloat(), dy, (x + strip).toFloat(), h + dy)
            canvas.drawBitmap(bmp, src, dst, paint)
            x += strip
        }
    }
}

/** Ligne RVB animée de bord à bord, qui fait le tour de la zone nom de config + sauvegarde. */
private class RgbBar(context: android.content.Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val shift = Matrix()
    private val box = RectF()

    init { gravity = Gravity.CENTER; setWillNotDraw(false) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        line.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.RED), null, Shader.TileMode.REPEAT)
    }

    override fun onDraw(canvas: Canvas) {
        if (childCount == 0) return
        val first = getChildAt(0)
        val last = getChildAt(childCount - 1)
        box.set(first.left.toFloat(), first.top.toFloat(), last.right.toFloat(), last.bottom.toFloat())
        box.inset(-10 * dp, -5 * dp)
        shift.setTranslate(SystemClock.uptimeMillis() % 4000 / 4000f * width, 0f) // le dégradé défile : 1 tour en 4 s
        line.shader?.setLocalMatrix(shift)
        val y = height / 2f
        canvas.drawLine(0f, y, box.left, y, line)
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, line)
        canvas.drawLine(box.right, y, width.toFloat(), y, line)
        postInvalidateOnAnimation()
    }
}

/** Fond synthwave : dégradé coucher de soleil + grille en perspective. */
private class SynthwaveBackground : Drawable() {
    private val fill = Paint()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 46, 151); strokeWidth = 2f }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#0D0221"), Color.parseColor("#2A0845"), Color.parseColor("#3B0A45")),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, fill)

        val horizon = h * 0.55f
        var y = horizon
        var step = 6f
        while (y < h) { canvas.drawLine(0f, y, w, y, grid); step *= 1.35f; y += step }
        for (i in -12..12) {
            val x = w / 2 + i * w / 12
            canvas.drawLine(w / 2 + i * w / 48, horizon, x, h, grid)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
