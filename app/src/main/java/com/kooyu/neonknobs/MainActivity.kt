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
import android.graphics.RadialGradient
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
    // 2 banks par ligne, 24 mémoires {n, cc, c, v} : 0-11 = bank A / switch OFF (CC 20-31), 12-23 = bank B / switch ON (CC 32-43).
    // Nom, CC, valeur et couleur sont propres à chaque mémoire (chaque CC a les siens).
    private val slots = Array(24) { JSONObject() }
    private val switchOn = BooleanArray(2)
    private val switches = mutableListOf<RowSwitch>()
    private lateinit var status: TextView
    private lateinit var configName: TextView
    private lateinit var wave: WaveOverlay
    private lateinit var root: LinearLayout
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private val main = Handler(Looper.getMainLooper())
    private var midiManager: MidiManager? = null
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p)
        }
        root.addView(TextView(this).apply {
            text = "Kooyu FX Mini Studio Laser Terminator"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            textSize = 20f
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            setShadowLayer(10 * resources.displayMetrics.density, 0f, 0f, KnobView.NEON[0])
        })
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            textSize = 14f
        }
        root.addView(status)

        for (j in 0 until 24) {
            val k = j % 12
            slots[j] = JSONObject()
                .put("n", prefs.getString("name$j", null) ?: "Potard ${k + 1}")
                .put("cc", prefs.getInt("cc$j", 20 + j)) // OFF : potard 1 = CC 20 … 12 = CC 31 · ON : potard 1 = CC 32 … 12 = CC 43
                .put("c", prefs.getInt("col$j", KnobView.NEON[k / 6]))
                .put("v", prefs.getInt("val$j", 0))
        }
        for (row in 0..1) {
            switchOn[row] = prefs.getBoolean("sw$row", false)
            if (row == 1) root.addView(configBar())
            val line = LinearLayout(this)
            val sw = RowSwitch(this).apply {
                on = switchOn[row]
                contentDescription = "Switch ligne ${row + 1}"
                setOnClickListener { toggleRow(row) }
            }
            switches += sw
            line.addView(sw, LinearLayout.LayoutParams((72 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
            for (col in 0..5) {
                val knob = KnobView(this, KnobView.NEON[row]).apply {
                    onValueChange = { sendCc(it.cc, it.value) }
                    onEdit = { editKnob(it, withCc = true) }
                    onRename = { editKnob(it, withCc = false) }
                }
                knobs += knob
                line.addView(knob, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        knobs.indices.forEach(::show)
        applyStyle(prefs.getBoolean("realistic", false))
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
        knobs.indices.forEach(::store)
        prefs.edit().apply {
            slots.forEachIndexed { j, o ->
                putString("name$j", o.getString("n")); putInt("cc$j", o.getInt("cc"))
                putInt("val$j", o.getInt("v")); putInt("col$j", o.getInt("c"))
            }
            putBoolean("sw0", switchOn[0]); putBoolean("sw1", switchOn[1])
        }.apply()
    }

    // ---------- Switchs de ligne ----------
    // Le potard à l'écran affiche la mémoire active ; on recopie avant de basculer.

    private fun slotOf(k: Int) = k + if (switchOn[k / 6]) 12 else 0

    private fun store(k: Int) {
        val v = knobs[k]
        slots[slotOf(k)] = JSONObject().put("n", v.name).put("cc", v.cc).put("c", v.color).put("v", v.value)
    }

    /** Nom, CC, valeur et couleur de la bank active. */
    private fun show(k: Int) {
        val o = slots[slotOf(k)]
        knobs[k].apply { name = o.getString("n"); cc = o.getInt("cc"); color = o.getInt("c"); value = o.getInt("v") }
    }

    /** Interne à l'appli : bascule la ligne de bank (CC + valeurs), rien n'est envoyé en MIDI. */
    private fun toggleRow(row: Int) {
        val ks = row * 6 until row * 6 + 6
        ks.forEach(::store)
        switchOn[row] = !switchOn[row]
        ks.forEach(::show)
        switches[row].on = switchOn[row]
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
            .setTitle("Potard ${knobs.indexOf(k) + 1}" + if (switchOn[knobs.indexOf(k) / 6]) " · bank B" else " · bank A")
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
        val style = pill("🎨", KnobView.NEON[2]).apply { setOnClickListener { applyStyle(!knobs[0].realistic) } }
        return RgbBar(this).apply {
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            addView(configName)
            addView(save, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (10 * d).toInt() })
            addView(style, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (10 * d).toInt() })
        }
    }

    /** Bascule néon / réaliste, mémorisé tout de suite. */
    private fun applyStyle(realistic: Boolean) {
        prefs.edit().putBoolean("realistic", realistic).apply()
        root.background = if (realistic) PanelBackground() else SynthwaveBackground()
        knobs.forEach { it.realistic = realistic }
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
        knobs.indices.forEach(::store)
        val knobsJson = JSONArray(slots.toList()) // les 2 positions de switch, 24 mémoires
        prefs.edit().putString("configs", configs().put(name, knobsJson).toString()).putString("current", name).apply()
        showConfigName()
        if (animate) wave.play()
    }

    // Les valeurs reviennent à l'écran sans être envoyées au PC, comme au démarrage de l'appli.
    private fun loadConfig(name: String) {
        val a = configs().optJSONArray(name) ?: return
        // les configs d'avant les switchs n'ont que 12 mémoires : les 12 "switch ON" restent telles quelles
        for (j in slots.indices) a.optJSONObject(j)?.let { slots[j] = it }
        knobs.indices.forEach(::show)
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

/** Petit bouton poussoir avec témoin lumineux au-dessus. */
private class RowSwitch(context: android.content.Context) : View(context) {
    var on = false
        set(v) { field = v; isSelected = v; invalidate() }
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // halo du témoin
        isClickable = true
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val size = minOf(width * 0.66f, height * 0.4f)
        val cy = height / 2f + size * 0.3f

        // témoin
        val ly = cy - size * 0.95f
        val lr = size * 0.2f
        if (on) {
            paint.shader = RadialGradient(cx - lr * 0.3f, ly - lr * 0.3f, lr * 1.3f, Color.WHITE, 0xFFFF1744.toInt(), Shader.TileMode.CLAMP)
            paint.setShadowLayer(10 * dp, 0f, 0f, 0xFFFF1744.toInt())
        } else {
            paint.shader = RadialGradient(cx - lr * 0.3f, ly - lr * 0.3f, lr * 1.3f, 0xFF5A1E26.toInt(), 0xFF1E0A0D.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, ly, lr, paint)
        paint.clearShadowLayer()

        // poussoir : socle noir + capuchon qui s'enfonce quand on appuie
        val h = size / 2
        rect.set(cx - h, cy - h, cx + h, cy + h)
        paint.shader = null
        paint.color = 0xFF0A0A0C.toInt()
        paint.setShadowLayer(6 * dp, 0f, 3 * dp, Color.argb(180, 0, 0, 0))
        canvas.drawRoundRect(rect, size * 0.2f, size * 0.2f, paint)
        paint.clearShadowLayer()
        val inset = if (isPressed) size * 0.16f else size * 0.1f
        rect.inset(inset, inset)
        val (top, bottom) = if (isPressed) 0xFF1C1C20.toInt() to 0xFF3A3A40.toInt() else 0xFF55555C.toInt() to 0xFF1C1C20.toInt()
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, size * 0.14f, size * 0.14f, paint)
        paint.shader = null
    }
}

/** Fond façon façade de matériel studio : alu anthracite brossé + 4 vis. */
private class PanelBackground : Drawable() {
    private val fill = Paint()
    private val brush = Paint().apply { strokeWidth = 1f }
    private val screw = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 20, 22); strokeCap = Paint.Cap.ROUND }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        fill.shader = LinearGradient(0f, 0f, 0f, h, 0xFF2E2F34.toInt(), 0xFF131416.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, fill)
        val rnd = java.util.Random(7) // même grain à chaque dessin
        var y = 0f
        while (y < h) {
            brush.color = Color.argb(4 + rnd.nextInt(10), 255, 255, 255)
            canvas.drawLine(0f, y, w, y, brush)
            y += 2f + rnd.nextInt(3)
        }
        val s = minOf(w, h) * 0.012f
        slot.strokeWidth = s * 0.35f
        for ((x, yy) in listOf(s * 2.5f to s * 2.5f, w - s * 2.5f to s * 2.5f, s * 2.5f to h - s * 2.5f, w - s * 2.5f to h - s * 2.5f)) {
            screw.shader = RadialGradient(x - s * 0.3f, yy - s * 0.3f, s * 1.4f,
                0xFFB8B8BE.toInt(), 0xFF3A3A40.toInt(), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, yy, s, screw)
            canvas.drawLine(x - s * 0.6f, yy - s * 0.25f, x + s * 0.6f, yy + s * 0.25f, slot)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
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
