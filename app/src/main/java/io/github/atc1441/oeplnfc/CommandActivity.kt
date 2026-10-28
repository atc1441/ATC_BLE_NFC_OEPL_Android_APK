package io.github.atc1441.oeplnfc

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the whole command set from one screen: pick a command, fill in what it
 * offers, hold the phone against the tag.
 *
 * Built from [Commands] rather than as a screen per command - there are over
 * fifty between the tag commands and the debug channel, and a hand-written form
 * each would be both enormous and the first thing to rot when the firmware
 * gains a parameter.
 *
 * Generic does not have to mean twelve numbered boxes, though. A parameter says
 * what it is - a range with a unit, a choice, a flag, a pin, a colour - and
 * that is what gets built: a slider with the value in milliseconds under it, a
 * named pin list, a colour picker. The one command whose shape a byte list
 * cannot describe at all, the LED pattern, has a form of its own.
 */
class CommandActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var adapter: NfcAdapter? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var filterField: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var chosen: TextView
    private lateinit var help: TextView
    private lateinit var danger: TextView
    private lateinit var wire: TextView
    private lateinit var paramBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var nfcState: TextView
    private lateinit var out: TextView
    private lateinit var rawField: EditText
    private lateinit var armButton: Button
    private lateinit var disarmButton: Button

    /** One input, however it is drawn, reduced to the string the encoder takes. */
    private fun interface Field {
        fun value(): String
    }

    private val fields = ArrayList<Field>()
    private val led = LedPattern()

    @Volatile private var spec: CommandSpec? = null

    /**
     * What the next tap should send, if anything, and how many taps it has
     * already cost. Kept armed across a failure on purpose: a phone that slid
     * off mid-command is the normal case, not a reason to make someone press
     * the button again.
     */
    @Volatile private var armedCmd = -1
    @Volatile private var armedPayload: ByteArray? = null
    @Volatile private var armedSpec: CommandSpec? = null
    @Volatile private var armedName = ""
    @Volatile private var attempt = 0

    /**
     * Every command there is, in one list, with the set it belongs to.
     *
     * It used to be two dropdowns: pick "Tag commands" or "Debug channel",
     * then hunt the command in a spinner of forty-odd entries that only opens
     * ten at a time. Finding anything meant knowing which of the two sets it
     * was in - which is firmware trivia, not something to ask of whoever is
     * holding the phone - and the only way to look was to open both and
     * scroll. One list with a search box answers "where is the thing that
     * does X" by typing X.
     *
     * The set is kept, but as a word on the row rather than a gate in front
     * of it: it still matters that a debug command is a debug command.
     */
    private fun catalogue(): List<Pair<CommandSpec, String>> =
        Commands.tag.map { it to "tag" } + Commands.debug.map { it to "debug" }

    /** Those whose name, category, set or help text match what was typed. */
    private fun matching(): List<Pair<CommandSpec, String>> {
        val q = if (::filterField.isInitialized)
            filterField.text.toString().trim().lowercase() else ""
        if (q.isEmpty()) return catalogue()
        // Every word has to appear somewhere, in any order and in any of the
        // fields - so "lut read" and "read lut" both find the one command, and
        // so does "waveform" if the help text says waveform.
        val words = q.split(Regex("\\s+"))
        return catalogue().filter { (s, set) ->
            val hay = (s.fullName + " " + s.category + " " + set + " " +
                s.help).lowercase()
            words.all { hay.contains(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Commands"
        setContentView(buildUi())
        selectSpec(Commands.tag.first())
        showNfcState()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // --- small view helpers --------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = if (bold) 14f else 12f
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, if (bold) dp(12) else dp(2), 0, dp(2))
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(
            v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        status = TextView(this).apply {
            text = "Pick a command, then hold the phone against the tag."
            setTypeface(null, Typeface.BOLD)
        }
        root.addView(status)

        nfcState = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(2), 0, dp(2))
            setOnClickListener { armReader(manual = true) }
        }
        root.addView(nfcState)

        root.addView(label("Command", true))
        filterField = EditText(this).apply {
            hint = "Search — name, what it does, or \"debug\""
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = buildList()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(filterField)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        chosen = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
        }
        root.addView(chosen)

        help = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(6), 0, dp(4))
        }
        root.addView(help)

        danger = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#C62828"))
            setPadding(0, 0, 0, dp(4))
            visibility = View.GONE
        }
        root.addView(danger)

        paramBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(paramBox)

        wire = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(wire)

        armButton = button("Arm — then tap the tag") { armCommand() }
        disarmButton = button("Cancel") { disarm("Cancelled.") }.apply {
            visibility = View.GONE
        }
        root.addView(armButton)
        root.addView(disarmButton)

        root.addView(label("Raw command", true))
        root.addView(label("Two command id bytes, big endian, then the payload. 001B7F.. is a debug echo."))
        rawField = EditText(this).apply {
            hint = "0005"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        root.addView(rawField)
        root.addView(button("Arm raw — then tap the tag") { armRaw() })

        root.addView(button("Clear output") { out.text = "" })

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(out)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * The list of commands, under category headings, filtered by the search.
     *
     * Rebuilt rather than updated: it is forty rows of text and a few taps
     * per session, so the simple thing is also the fast enough thing.
     */
    private fun buildList() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        val rows = matching()
        if (rows.isEmpty()) {
            listBox.addView(label("Nothing matches that."))
            return
        }
        var heading = ""
        for ((s, set) in rows) {
            val head = if (set == "debug") "${s.category}  (debug channel)" else s.category
            if (head != heading) {
                heading = head
                listBox.addView(label(head, true))
            }
            val picked = s === spec
            listBox.addView(TextView(this).apply {
                text = (if (picked) "▸  " else "    ") + s.fullName
                textSize = 15f
                setPadding(dp(4), dp(7), dp(4), dp(7))
                if (picked) setTypeface(null, Typeface.BOLD)
                setOnClickListener { selectSpec(s) }
            })
        }
    }

    // --- the form ------------------------------------------------------------

    private fun selectSpec(s: CommandSpec) {
        spec = s
        chosen.text = s.fullName
        // The row that is selected is shown as such, so the list and the form
        // below it cannot disagree about what is about to be sent.
        buildList()
        help.text = buildString {
            append(s.help)
            if (s.loop != Loop.NONE)
                append("\n\nThis screen keeps asking until the tag says it is done.")
            if (s.reboots) append("\n\nThe tag restarts, so no reply comes back.")
            if (!s.replies) append("\n\nThis command answers with nothing.")
        }
        danger.visibility = if (s.danger.isEmpty()) View.GONE else View.VISIBLE
        danger.text = "⚠  ${s.danger}"

        paramBox.removeAllViews()
        fields.clear()

        if (s.editor == Editor.LED) buildLedEditor() else for (p in s.params) addParam(p)
        showWire()
    }

    private fun addParam(p: Param) {
        val head = p.label +
            (if (p.hint.isNotEmpty()) "  (${p.hint})" else "") +
            (if (p.required) "  — required" else "")

        when (p.widget) {
            Widget.CHOICE -> {
                paramBox.addView(label(head))
                val sp = Spinner(this)
                sp.adapter = ArrayAdapter(
                    this, android.R.layout.simple_spinner_dropdown_item,
                    p.options!!.map { it.first }
                )
                val idx = p.options.indexOfFirst { it.second == parse(p.default) }
                if (idx >= 0) sp.setSelection(idx)
                sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(a: AdapterView<*>?, v: View?, i: Int, id: Long) =
                        showWire()

                    override fun onNothingSelected(a: AdapterView<*>?) {}
                }
                paramBox.addView(sp)
                fields.add { p.options[sp.selectedItemPosition].second.toString() }
            }

            Widget.SWITCH -> {
                // Two named values, one of them "no": a checkbox says that far
                // better than a dropdown you have to open to see the state of.
                val off = p.options!![0].second
                val on = p.options[1].second
                val box = CheckBox(this).apply {
                    text = head
                    textSize = 12f
                    isChecked = parse(p.default) == on
                    setOnCheckedChangeListener { _, _ -> showWire() }
                }
                paramBox.addView(box)
                fields.add { (if (box.isChecked) on else off).toString() }
            }

            Widget.SLIDER -> {
                val lbl = label("")
                paramBox.addView(lbl)
                val bar = SeekBar(this).apply {
                    max = p.max - p.min
                    progress = (parse(p.default) - p.min).coerceIn(0, p.max - p.min)
                }
                fun show() = run { lbl.text = "$head:  ${p.show(bar.progress + p.min)}" }
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) {
                        show(); showWire()
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                show()
                paramBox.addView(bar)
                fields.add { (bar.progress + p.min).toString() }
            }

            Widget.NOW -> {
                val lbl = label(head)
                paramBox.addView(lbl)
                val e = EditText(this).apply {
                    setText(p.default)
                    setSingleLine()
                }
                val readable = label("")
                // Read back in UTC on purpose. The value is already local time
                // - the firmware has no timezone and prints the stamp straight
                // out - so formatting it in the phone's zone would shift it a
                // second time and show a clock neither end agrees with.
                val asWallClock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                fun show() {
                    val v = e.text.toString().trim().toLongOrNull()
                    readable.text = if (v == null || v == 0L) "not set"
                    else "the tag will show " + asWallClock.format(Date(v * 1000))
                    showWire()
                }
                e.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) = show()
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                paramBox.addView(e)
                paramBox.addView(row(button("Now (local time)") {
                    // Local, not UTC: the firmware has no timezone and prints
                    // the stamp straight out, so UTC would show the wrong time.
                    val now = System.currentTimeMillis()
                    val t = now / 1000 +
                        java.util.TimeZone.getDefault().getOffset(now) / 1000
                    e.setText(t.toString())
                }))
                paramBox.addView(readable)
                show()
                fields.add { e.text.toString() }
            }

            Widget.COLOR -> {
                paramBox.addView(label(head))
                val holder = colourPicker(parse(p.default)) { showWire() }
                paramBox.addView(holder.view)
                fields.add { holder.get().toString() }
            }

            Widget.TEXT -> {
                paramBox.addView(label(head))
                val e = EditText(this).apply {
                    setText(p.default)
                    setSingleLine()
                }
                val count = if (p.type == ParamType.HEX || p.type == ParamType.MAC8)
                    label("") else null
                fun show() {
                    count?.text = try {
                        val n = Commands.hexBytes(e.text.toString()).size
                        if (p.bytes > 0) "$n of ${p.bytes} bytes" else "$n bytes"
                    } catch (ex: Exception) {
                        ex.message ?: "bad hex"
                    }
                    showWire()
                }
                e.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) = show()
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                paramBox.addView(e)
                if (count != null) {
                    paramBox.addView(count)
                    show()
                }
                fields.add { e.text.toString() }
            }
        }
    }

    private fun parse(s: String): Int {
        val t = s.trim()
        if (t.isEmpty()) return 0
        return try {
            if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toInt(16)
            else t.toInt()
        } catch (_: Exception) {
            0
        }
    }

    /** The value each field currently holds, as the encoder wants it. */
    private fun values(): List<String> = fields.map { it.value() }

    // --- the colour picker ---------------------------------------------------

    private class Picker(val view: View, val get: () -> Int)

    /**
     * Three sliders and a swatch for one packed colour byte.
     *
     * Red and green have eight steps and blue four, because that is what
     * flashled() can actually produce: it slices the flash into seven
     * sub-steps and compares each component against them, comparing blue
     * twice. Offering 0..255 here would be offering values the tag rounds away.
     */
    private fun colourPicker(initial: Int, onChange: (Int) -> Unit): Picker {
        var r = LedPattern.red(initial)
        var g = LedPattern.green(initial)
        var b = LedPattern.blue(initial)

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val swatch = View(this)
        val caption = label("")

        fun refresh() {
            swatch.setBackgroundColor(LedPattern.argb(r, g, b))
            caption.text = "red $r/7   green $g/7   blue $b/3   →  byte 0x%02X"
                .format(LedPattern.colour(r, g, b))
            onChange(LedPattern.colour(r, g, b))
        }

        fun slider(name: String, maxV: Int, start: Int, set: (Int) -> Unit) {
            val lbl = label("")
            val bar = SeekBar(this).apply {
                max = maxV
                progress = start
            }
            fun show() = run { lbl.text = "$name  ${bar.progress}/$maxV" }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) {
                    set(v); show(); refresh()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            show()
            box.addView(lbl)
            box.addView(bar)
        }

        box.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        slider("Red", 7, r) { r = it }
        slider("Green", 7, g) { g = it }
        slider("Blue", 3, b) { b = it }
        box.addView(caption)
        refresh()
        return Picker(box) { LedPattern.colour(r, g, b) }
    }

    // --- the LED pattern editor ----------------------------------------------

    /**
     * The LED pattern as what it is: three groups of flashes with a colour, a
     * count and a spacing, and a repeat count over the lot.
     *
     * The bytes are packed two values to a byte, so the generic form would be
     * twelve boxes holding things like 0x23 - which is a transcription exercise
     * rather than a control. Here the same twelve bytes are sliders with the
     * spacing in milliseconds and the colour as a swatch, and the hex is shown
     * anyway for anyone who wants to send it another way.
     */
    private fun buildLedEditor() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val summary = label("")

        val refresh = {
            val ms = led.durationMs()
            summary.text = "One run: %s\nSends: %s".format(
                if (ms < 0) "until something clears the LED"
                else "%.1f s".format(ms / 1000.0),
                led.encode().toHex()
            )
            showWire()
        }

        fun slider(
            parent: LinearLayout, name: String, min: Int, max: Int, start: Int,
            unit: (Int) -> String, set: (Int) -> Unit
        ) {
            val lbl = label("")
            val bar = SeekBar(this).apply {
                this.max = max - min
                progress = (start - min).coerceIn(0, max - min)
            }
            fun show() = run { lbl.text = "$name  ${unit(bar.progress + min)}" }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, user: Boolean) {
                    set(v + min); show(); refresh()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            show()
            parent.addView(lbl)
            parent.addView(bar)
        }

        // Presets first: the interesting part of this command is the shape of
        // the sequence, and nobody finds a good one from twelve sliders.
        box.addView(label("Start from", true))
        var presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, preset) in LedPattern.presets.withIndex()) {
            if (i > 0 && i % 3 == 0) {
                box.addView(presetRow)
                presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            }
            presetRow.addView(
                button(preset.first) {
                    led.copyFrom(preset.second)
                    // Rebuilt rather than updated: every slider's position is
                    // part of the pattern, and putting them all back by hand is
                    // more code than building the block again.
                    spec?.let { selectSpec(it) }
                    arm("LED pattern: ${preset.first}", Cmd.SET_LED, led.encode(), spec)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        box.addView(presetRow)

        slider(
            box, "Brightness and flash length", 1, 16, led.brightness,
            { "$it/16  (about %.1f ms per flash)".format(it * 0.7) }
        ) { led.brightness = it }

        slider(
            box, "Repeat the sequence", 1, LedPattern.ENDLESS, led.repeats,
            { if (it >= LedPattern.ENDLESS) "until cleared" else "$it ×" }
        ) { led.repeats = it }

        for (i in 0 until 3) {
            val g = led.groups[i]
            box.addView(label("Group ${i + 1}", true))
            box.addView(
                colourPicker(LedPattern.colour(g.r, g.g, g.b)) { v ->
                    g.r = LedPattern.red(v)
                    g.g = LedPattern.green(v)
                    g.b = LedPattern.blue(v)
                    refresh()
                }.view
            )
            slider(box, "  Flashes", 1, 15, g.flashes, { "$it" }) { g.flashes = it }
            slider(
                box, "  Spacing", 0, 15, g.spacing,
                { "$it × 100 ms = ${maxOf(it, 1) * LedPattern.TICK_MS} ms between flashes" }
            ) { g.spacing = it }
        }

        box.addView(summary)
        box.addView(button("Send 'LED off' instead") {
            arm("LED off", Cmd.SET_LED, LedPattern.off(), spec)
        })
        paramBox.addView(box)
        refresh()
    }

    // --- what will be sent ---------------------------------------------------

    /** The payload for the form as it stands, or null with a reason. */
    private fun payload(s: CommandSpec): Pair<ByteArray?, String> {
        return try {
            if (s.editor == Editor.LED) {
                Pair(led.encode(), "")
            } else {
                val body = Commands.encode(s.params, values())
                Pair(if (s.sub != null) byteArrayOf(s.sub.toByte()) + body else body, "")
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Show the exact bytes the tag will see. Not decoration: half the commands
     * here are a length or an endianness away from a generic error, and seeing
     * the frame is how you tell which.
     */
    private fun showWire() {
        val s = spec ?: return
        val (p, why) = payload(s)
        wire.text = if (p == null) "cannot build the payload: $why"
        else "cmd 0x%04X   %d byte%s   %s".format(
            s.id, p.size, if (p.size == 1) "" else "s",
            if (p.isEmpty()) "(no payload)" else p.toHex()
        )
    }

    // --- arming -------------------------------------------------------------

    private fun armCommand() {
        val s = spec ?: return
        // Encoded now rather than at tap time, so a typo is a message here
        // instead of a failed tap later.
        val (p, why) = payload(s)
        if (p == null) {
            say("Cannot build the payload: $why")
            return
        }
        if (s.reboots || s.danger.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(s.name)
                .setMessage(
                    buildString {
                        if (s.danger.isNotEmpty()) append("⚠  ${s.danger}\n\n")
                        append(s.help.lineSequence().first())
                        if (s.reboots) append("\n\nThis restarts the tag.")
                        append("\n\nSends: ${p.toHex()}")
                    }
                )
                .setPositiveButton("Arm") { _, _ -> arm(s.name, s.id, p, s) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            arm(s.name, s.id, p, s)
        }
    }

    private fun armRaw() {
        val hex = rawField.text.toString()
        val b = try {
            val v = Commands.hexBytes(hex)
            if (v.size < 2) throw IllegalArgumentException("need at least the two id bytes")
            v
        } catch (e: Exception) {
            say("Bad raw command: ${e.message}")
            return
        }
        val cmd = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        arm("raw 0x%04X".format(cmd), cmd, b.copyOfRange(2, b.size), null)
    }

    private fun arm(name: String, cmd: Int, body: ByteArray, s: CommandSpec?) {
        armedName = name
        armedCmd = cmd
        armedPayload = body
        armedSpec = s
        attempt = 0
        disarmButton.visibility = View.VISIBLE
        status.text = "Armed: $name. Hold the phone against the tag."
    }

    private fun disarm(why: String) {
        armedPayload = null
        armedSpec = null
        armedCmd = -1
        attempt = 0
        post {
            disarmButton.visibility = View.GONE
            status.text = why
        }
    }

    // --- reader mode --------------------------------------------------------

    /**
     * Claimed on every way back in and dropped only in onStop, for the same
     * reason as on the main screen: a window that is small or in split screen
     * is visible and *paused*, gets no onResume, and used to leave the taps to
     * the platform from then on.
     */
    private fun armReader(manual: Boolean = false) {
        if (Build.VERSION.SDK_INT < 19) return
        adapter = NfcAdapter.getDefaultAdapter(this)
        val a = adapter
        readerWhy = when {
            a == null -> "this phone has no NFC"
            !a.isEnabled -> "NFC is switched off in Android settings"
            else -> try {
                a.enableReaderMode(
                    this, this,
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null
                )
                readerOn = true
                if (manual) say("Reader mode taken back.")
                ""
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
        }
        if (readerWhy.isNotEmpty()) readerOn = false
        showNfcState()
    }

    private var readerOn = false
    private var readerWhy = ""

    private fun showNfcState() {
        if (!::nfcState.isInitialized) return
        nfcState.text = if (readerOn) "NFC reader active"
        else "NFC reader NOT active" +
            (if (readerWhy.isEmpty()) "" else " ($readerWhy)") + " — tap here to retry"
    }

    override fun onStart() {
        super.onStart()
        armReader()
    }

    override fun onResume() {
        super.onResume()
        armReader()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) armReader()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                adapter?.disableReaderMode(this)
            } catch (_: Exception) {
            }
        }
        readerOn = false
        readerWhy = "the app was in the background"
    }

    // --- the tap ------------------------------------------------------------

    // storedPin() and rememberPin() were here, for an automatic
    // unlock on the next tap. Gone with the rest of the NFC PIN
    // handling: the PIN guards BLE, and this screen talks NFC.

    override fun onTagDiscovered(tag: Tag) {
        val body = armedPayload
        if (body == null) {
            say("Nothing armed — pick a command and arm it first.")
            return
        }
        val iso = IsoDep.get(tag) ?: run { say("That tag does not speak ISO-DEP."); return }
        val s = armedSpec
        val cmd = armedCmd
        val name = armedName
        attempt++
        val n = attempt
        post { status.text = "$name, attempt $n…" }

        val nfc = OeplNfc(iso)
        try {
            nfc.open()
            nfc.selectApplication()
            // No unlock, same as the main screen: the PIN guards BLE, not
            // NFC. Set or clear a PIN over BLE; over NFC the tag refuses
            // those two commands outright, and this screen will say so.
            when {
                s != null && s.loop == Loop.LUT -> readWholeLut(nfc)
                s != null && s.loop == Loop.NDEF -> readWholeNdef(nfc, body)
                else -> runOne(nfc, cmd, body, s, name)
            }
            disarm("$name done. Arm another command.")
        } catch (e: Exception) {
            // Kept armed: the usual reason a command fails is that the phone
            // moved, and the next tap should just carry on.
            say("${e.javaClass.simpleName}: ${e.message}")
            post {
                status.text = "$name failed on attempt $n — tap again, or cancel."
            }
        } finally {
            nfc.close()
        }
    }

    private fun runOne(
        nfc: OeplNfc, cmd: Int, body: ByteArray, s: CommandSpec?, name: String
    ) {
        say("> $name  cmd 0x%04X  %s".format(cmd, body.toHex()))
        val reply = try {
            nfc.command(cmd, body)
        } catch (e: Exception) {
            // A rebooting tag drops the link mid-exchange; that is the expected
            // outcome, not a failure worth shouting about.
            if (s?.reboots == true) {
                say("  tag went away, as expected for this command")
                return
            }
            throw e
        }
        report(reply, s)
    }

    /**
     * READ_LUT puts the panel's waveform table in the frame buffer and then
     * hands it over 230 bytes at a time until it answers "done". Walking that
     * by hand is dozens of taps on a button, so the loop lives here.
     */
    private fun readWholeLut(nfc: OeplNfc) {
        say("> Read the panel LUT")
        val start = nfc.command(Cmd.READ_LUT)
        if (start == null || start.cmd != Cmd.ACK_LUT_START) {
            report(start, null)
            throw OeplException("the tag did not start a LUT read")
        }
        val all = java.io.ByteArrayOutputStream()
        var chunks = 0
        while (chunks < MAX_LUT_CHUNKS) {
            val r = nfc.commandExpecting(Cmd.READ_LUT_NEXT)
            if (r.cmd == Cmd.ACK_LUT_DONE) break
            if (r.cmd != Cmd.ACK_LUT_DATA) {
                report(r, null)
                throw OeplException("unexpected reply during the LUT read")
            }
            all.write(r.payload)
            chunks++
        }
        val bytes = all.toByteArray()
        say("  < ${bytes.size} bytes in $chunks chunks\n" + Commands.hexDump(0, bytes))
    }

    /**
     * GET_NDEF answers in 200 byte windows and says how long the whole thing
     * is, so one tap can fetch all of it rather than leaving the arithmetic to
     * whoever is holding the phone.
     */
    private fun readWholeNdef(nfc: OeplNfc, body: ByteArray) {
        var off = if (body.size >= 2)
            (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8) else 0
        val all = java.io.ByteArrayOutputStream()
        var total = -1
        var source = 0
        var guard = 0
        while (guard++ < MAX_NDEF_WINDOWS) {
            val r = nfc.commandExpecting(
                Cmd.GET_NDEF, byteArrayOf(off.toByte(), (off shr 8).toByte())
            )
            if (r.cmd != Cmd.ACK_NDEF_DATA || r.payload.size < 5) {
                report(r, null)
                return
            }
            source = r.payload[0].toInt() and 0xFF
            total = (r.payload[1].toInt() and 0xFF) or ((r.payload[2].toInt() and 0xFF) shl 8)
            val chunk = r.payload.copyOfRange(5, r.payload.size)
            if (chunk.isEmpty()) break
            all.write(chunk)
            off += chunk.size
            if (off >= total) break
        }
        val msg = all.toByteArray()
        say(
            "  < NDEF_DATA  source 0x%02X, %d bytes\n%s\n\n%s".format(
                source, total, NdefCodec.describe(msg), Commands.hexDump(0, msg)
            )
        )
    }

    private fun report(reply: OeplNfc.Reply?, s: CommandSpec?) {
        if (reply == null) {
            say(if (s?.replies == false) "  no reply, as expected" else "  no reply")
            return
        }
        val name = Commands.replyNames[reply.cmd] ?: "0x%04X".format(reply.cmd)
        if (reply.cmd == Cmd.ERR) {
            say("  < $name — the tag rejected it")
            return
        }
        val decoded = s?.decode?.invoke(reply.payload)
        if (decoded != null) say("  < $name\n$decoded")
        else if (reply.payload.isEmpty()) say("  < $name")
        else say("  < $name\n" + Commands.hexDump(0, reply.payload))
    }

    private fun say(line: String) = post {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        out.text = "$stamp  $line\n\n${out.text}"
    }

    private fun post(block: () -> Unit) = main.post(block)

    companion object {
        /** 230 bytes a chunk; no panel's LUT is anywhere near this long. */
        private const val MAX_LUT_CHUNKS = 200

        /** 200 bytes a window over a 512 byte store, with room to spare. */
        private const val MAX_NDEF_WINDOWS = 8

        fun start(ctx: Context) =
            ctx.startActivity(android.content.Intent(ctx, CommandActivity::class.java))
    }
}
