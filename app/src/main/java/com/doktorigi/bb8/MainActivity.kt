package com.doktorigi.bb8

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.RECORD_AUDIO
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.roundToInt

private val COLORS = listOf("blue" to 0x0044ff, "red" to 0xff0000, "green" to 0x00ff00,
    "orange" to 0xff6600, "purple" to 0x8800ff, "white" to 0xffffff)

private val ICONS = mapOf("hello" to "👋", "yes" to "👍", "no" to "🙅", "look" to "👀", "spin" to "🌀",
    "happy" to "😊", "excited" to "🤩", "sad" to "😢", "scared" to "😱", "dance" to "💃",
    "forward" to "⬆️", "back" to "⬇️", "left" to "⬅️", "right" to "➡️")

private enum class Style { PRIMARY, SECONDARY, DANGER, GHOST }

class MainActivity : Activity() {
    private lateinit var bb: BB8
    private lateinit var moves: Moves
    private lateinit var watch: Watch
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var cueBox: EditText
    private var recognizer: SpeechRecognizer? = null
    private var afterGrant: (() -> Unit)? = null
    private val cueFile by lazy { File(filesDir, "cues.txt") }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.statusBarColor = Palette.SPACE
        window.navigationBarColor = Palette.SPACE
        status = TextView(this).apply {
            textSize = 13f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = pill(this@MainActivity, intArrayOf(Palette.CONTROL), Palette.CONTROL_EDGE)
        }
        showStatus("Not connected")
        bb = BB8(this) { msg -> runOnUiThread { showStatus(msg) } }
        moves = Moves(bb)
        watch = Watch(moves) { ms -> runOnUiThread { clock.text = formatTime(ms) } }

        val column = vertical().apply { setPadding(dp(16), dp(20), dp(16), dp(10)) }
        column.addView(header())
        column.addView(row(
            button("Connect BB-8", Style.PRIMARY) { withPerms(BLUETOOTH_SCAN, BLUETOOTH_CONNECT) { bb.connect() } },
            button("■  Stop", Style.DANGER) { moves.stop() }))
        val tabs = row().apply {
            background = pill(this@MainActivity, intArrayOf(Palette.CONTROL), Palette.CONTROL_EDGE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        column.addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12); bottomMargin = dp(12) })
        val content = FrameLayout(this)
        column.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val panels = listOf("Drive" to drivePanel(), "Tricks" to tricksPanel(), "Patrol" to patrolPanel(),
            "Voice" to voicePanel(), "Watch" to watchPanel())
        val tabButtons = mutableListOf<Button>()
        val scrolls = panels.map { (_, panel) ->
            panel.background = card(this)
            panel.setPadding(dp(16), dp(16), dp(16), dp(16))
            ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(panel); content.addView(this) }
        }
        fun show(i: Int) = scrolls.forEachIndexed { k, s ->
            s.visibility = if (k == i) View.VISIBLE else View.GONE
            style(tabButtons[k], if (k == i) Style.PRIMARY else Style.GHOST)
        }
        panels.forEachIndexed { i, (name, _) ->
            tabButtons += button(name, Style.GHOST) { show(i) }.apply { textSize = 13f; setPadding(0, dp(9), 0, dp(9)) }
            tabs.addView(tabButtons[i], LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        show(0)
        setContentView(FrameLayout(this).apply { addView(Starfield(this@MainActivity)); addView(column) })
    }

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(12))
        addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.bb8_hero); contentDescription = "BB-8" },
            LayoutParams(dp(78), dp(86)))
        addView(vertical().apply {
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "BB-8"
                textSize = 34f
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                setTextColor(Palette.WHITE)
                letterSpacing = 0.08f
            })
            addView(TextView(this@MainActivity).apply {
                text = "REVIVED"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Palette.ORANGE)
                letterSpacing = 0.45f
            })
            addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        })
    }

    private fun showStatus(msg: String) {
        val dot = when {
            msg.startsWith("Connected") -> Palette.GOOD
            msg.startsWith("No ") || "fail" in msg || "didn't" in msg || "isn't" in msg || msg.startsWith("Disconnected") || msg.startsWith("Not") -> Palette.BAD
            else -> Palette.ORANGE
        }
        status.setTextColor(Palette.TEXT)
        status.text = android.text.SpannableString("● $msg").apply { setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, 0) }
    }

    override fun onPause() {
        super.onPause()
        cueFile.writeText(cueBox.text.toString())
    }

    override fun onDestroy() {
        watch.stop()
        moves.stop()
        bb.disconnect()
        recognizer?.destroy()
        super.onDestroy()
    }

    private fun drivePanel() = vertical().apply {
        val max = seek(255, 200)
        addView(Joystick(this@MainActivity) { amount, heading -> bb.drive((amount * max.progress).roundToInt(), heading) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(290)))
        addView(label("Max speed"))
        addView(max)
        addView(label("Droid color"))
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            COLORS.forEach { (name, rgb) ->
                addView(View(this@MainActivity).apply {
                    contentDescription = "$name light"
                    background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x60FFFFFF),
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0xFF000000.toInt() or rgb)
                            setStroke(dp(2), 0x88FFFFFF.toInt())
                        }, null)
                    setOnClickListener { bb.setBaseColor(rgb) }
                }, LayoutParams(dp(40), dp(40)).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) })
            }
        })
        addView(label("Aim"))
        addView(body("Light the tail, slide until the blue light points at you, then Set forward."))
        val aim = seek(359, 0) { bb.roll(0, it) }
        addView(aim)
        addView(row(
            button("Tail light on") { bb.tail(255); bb.roll(0, aim.progress) },
            button("Set forward", Style.PRIMARY) { bb.setForward(); bb.tail(0); aim.progress = 0 }))
    }

    private fun tricksPanel() = vertical().apply {
        addView(label("Tricks"))
        moves.names.chunked(3).forEach { group -> addView(row(*group.map { n -> trickButton(n) { moves.play(n) } }.toTypedArray())) }
        addView(toggle("Droid beeps from the phone", Beeps.enabled) { Beeps.enabled = it })
    }

    private fun patrolPanel() = vertical().apply {
        addView(label("Patrol"))
        addView(body("BB-8 wanders on its own, stopping now and then to look around. It can't see walls, so give it open floor."))
        addView(label("Patrol speed"))
        addView(seek(150, moves.patrolSpeed) { moves.patrolSpeed = maxOf(it, 30) })
        addView(row(button("Start patrol", Style.PRIMARY) { moves.startPatrol() }, button("Stop", Style.DANGER) { moves.stop() }))
    }

    private fun voicePanel() = vertical().apply {
        addView(label("Voice commands"))
        val heard = body("Tap Listen and say a move (" + moves.names.joinToString() + "), a color, \"patrol\" or \"stop\".")
        addView(button("🎙  Listen", Style.PRIMARY) { withPerms(RECORD_AUDIO) { listen(heard) } })
        addView(heard)
    }

    private fun listen(out: TextView) {
        if (watch.running) return toast("Stop Watch With Me first, it's using the mic")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return toast("No speech recognizer on this phone")
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) {
                val said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                out.text = "Heard \"$said\" → ${obey(said)}"
            }
            override fun onError(error: Int) { out.text = "Didn't catch that (error $error)" }
            override fun onReadyForSpeech(p: Bundle?) { out.text = "Listening…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(type: Int, p: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }

    /** Spoken phrase → action. Returns what BB-8 did. */
    private fun obey(said: String): String {
        val words = said.lowercase().split(Regex("\\W+"))
        COLORS.firstOrNull { it.first in words }?.let { bb.setBaseColor(it.second); return it.first }
        return when {
            "stop" in words || "halt" in words -> { moves.stop(); "stop" }
            "patrol" in words || "explore" in words -> { moves.startPatrol(); "patrol" }
            "go" in words && "forward" !in words -> { moves.play("forward"); "forward" }
            else -> moves.names.firstOrNull { it in words }?.also { moves.play(it) } ?: "nothing I know"
        }
    }

    private fun watchPanel() = vertical().apply {
        addView(label("Watch with me"))
        addView(body("Put BB-8 beside you facing the TV (aim it first). Start the movie and tap Start at the same moment, " +
            "or type where the movie is now. BB-8 reacts to loud moments through the mic and plays your cues on time.\n\n" +
            "Tap a reaction while the clock runs to save it as a cue for next viewing."))
        clock = TextView(this@MainActivity).apply {
            text = "0:00:00"
            textSize = 44f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(Palette.SABER)
            setShadowLayer(dp(10).toFloat(), 0f, 0f, Palette.SABER) // lightsaber glow
            setPadding(0, dp(8), 0, dp(8))
        }
        addView(clock)
        val from = field().apply { setText("0:00:00"); inputType = InputType.TYPE_CLASS_DATETIME; gravity = Gravity.CENTER }
        val mic = toggle("React to movie sound (mic)", true) {}
        addView(row(from,
            button("▶  Start", Style.PRIMARY) {
                withPerms(*if (mic.isChecked) arrayOf(RECORD_AUDIO) else emptyArray()) {
                    val t = parseTime(from.text.toString().trim()) ?: return@withPerms toast("Type a time like 1:02:03")
                    watch.micOn = mic.isChecked
                    watch.cues = parseCues(cueBox.text.toString())
                    watch.start(t)
                    window.addFlags(FLAG_KEEP_SCREEN_ON)
                }
            },
            button("■  Stop", Style.DANGER) { watch.stop(); moves.stop(); window.clearFlags(FLAG_KEEP_SCREEN_ON) }))
        addView(row(button("−1 s") { watch.nudge(-1000) }, button("+1 s") { watch.nudge(1000) }))
        addView(mic)
        addView(label("Mic sensitivity"))
        addView(seek(30, 21) { watch.sensitivityDb = 36.0 - it })
        addView(label("Reactions"))
        listOf("happy", "excited", "scared", "sad", "look", "spin", "yes", "no", "dance").chunked(3).forEach { group ->
            addView(row(*group.map { n ->
                trickButton(n) {
                    moves.play(n)
                    if (watch.running) cueBox.append("${formatTime(watch.movieMs)} $n\n")
                }
            }.toTypedArray()))
        }
        addView(label("Cue track"))
        addView(body("One per line: time and move, e.g. 0:05:10 excited"))
        cueBox = field().apply {
            setText(if (cueFile.exists()) cueFile.readText() else "# time  move   e.g.\n# 0:05:10 excited\n")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            minLines = 6
        }
        addView(cueBox)
    }

    private fun withPerms(vararg perms: String, then: () -> Unit) {
        if (perms.all { checkSelfPermission(it) == PERMISSION_GRANTED }) return then()
        afterGrant = then
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PERMISSION_GRANTED }) afterGrant?.invoke()
        else toast("BB-8 needs that permission")
        afterGrant = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Small orange caps heading for a section. */
    private fun label(s: String) = TextView(this).apply {
        text = s.uppercase()
        textSize = 12f
        letterSpacing = 0.18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Palette.ORANGE)
        setPadding(0, dp(14), 0, dp(6))
    }
    private fun body(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setTextColor(Palette.TEXT)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(2), 0, dp(6))
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        views.forEach { addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
    }

    private fun button(label: String, kind: Style = Style.SECONDARY, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        stateListAnimator = null
        textSize = 15f
        setPadding(dp(12), dp(13), dp(12), dp(13))
        style(this, kind)
        setOnClickListener { onClick() }
    }

    /** Big emoji over a label, for tricks and reactions. */
    private fun trickButton(name: String, onClick: () -> Unit) =
        button("${ICONS[name] ?: "✨"}\n${name.replaceFirstChar { it.uppercase() }}", onClick = onClick).apply {
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(6), dp(14), dp(6), dp(14))
            background = pill(this@MainActivity, intArrayOf(0xFF20293F.toInt(), 0xFF161D2E.toInt()), Palette.CONTROL_EDGE, 18f)
        }

    private fun style(b: Button, kind: Style) {
        b.typeface = if (kind == Style.GHOST) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        b.setTextColor(if (kind == Style.GHOST) Palette.MUTED else Palette.WHITE)
        b.background = when (kind) {
            Style.PRIMARY -> pill(this, intArrayOf(Palette.ORANGE_LIGHT, Palette.ORANGE))
            Style.DANGER -> pill(this, intArrayOf(0xFF3A1820.toInt()), Palette.BAD)
            Style.SECONDARY -> pill(this, intArrayOf(Palette.CONTROL), Palette.CONTROL_EDGE)
            Style.GHOST -> pill(this, intArrayOf(0x00000000))
        }
        if (kind == Style.DANGER) b.setTextColor(0xFFFF8A8F.toInt())
    }

    private fun field() = EditText(this).apply {
        setTextColor(Palette.WHITE)
        setHintTextColor(Palette.MUTED)
        textSize = 15f
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xFF0B1020.toInt())
            setStroke(dp(1), Palette.CONTROL_EDGE)
        }
    }

    private fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 14f
        setTextColor(Palette.TEXT)
        isChecked = on
        setPadding(0, dp(10), 0, dp(10))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.ORANGE, 0xFF9AA3B5.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x88F26522.toInt(), 0x44FFFFFF))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit = {}) = SeekBar(this).apply {
        this.max = max
        progress = value
        progressTintList = ColorStateList.valueOf(Palette.ORANGE)
        thumbTintList = ColorStateList.valueOf(Palette.ORANGE_LIGHT)
        progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }
}
