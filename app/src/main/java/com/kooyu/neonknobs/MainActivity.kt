package com.kooyu.neonknobs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

class MainActivity : Activity() {

    private val knobs = mutableListOf<KnobView>()
    private lateinit var status: TextView
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
            val line = LinearLayout(this)
            val neon = if (row == 0) Color.parseColor("#FF2E97") else Color.parseColor("#00F0FF")
            for (col in 0..5) {
                val i = row * 6 + col
                val knob = KnobView(this, neon).apply {
                    name = prefs.getString("name$i", null) ?: "Potard ${i + 1}"
                    cc = prefs.getInt("cc$i", 20 + i) // Potard 1 = CC 20 … Potard 12 = CC 31
                    value = prefs.getInt("val$i", 0)
                    onValueChange = { sendCc(it.cc, it.value) }
                    onEdit = ::editKnob
                }
                knobs += knob
                line.addView(knob, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
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
                putString("name$i", k.name); putInt("cc$i", k.cc); putInt("val$i", k.value)
            }
        }.apply()
    }

    override fun onDestroy() {
        midiManager?.unregisterDeviceCallback(deviceCallback)
        closeMidi()
        super.onDestroy()
    }

    // ---------- Réglages ----------

    private fun editKnob(k: KnobView) {
        val nameField = EditText(this).apply { setText(k.name); hint = "Nom"; setSingleLine() }
        val ccField = EditText(this).apply {
            setText(k.cc.toString()); hint = "CC (0-127)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(nameField); addView(ccField)
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
            }
            .setNegativeButton("Annuler", null)
            .show()
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
