package io.github.atc1441.oeplnfc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen. You arm an action, then hold the phone against the tag; the
 * action runs inside the reader-mode callback and reports back.
 *
 * That shape is deliberate. An NFC tag is only reachable while it is in the
 * field, and an image upload takes seconds of continuous exchange, so there is
 * no sensible "connect now, act later" model to offer.
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    // How often a configuration is written before giving up on it
    // matching when read back. Three: a mangled exchange is common,
    // three in a row against the same bytes is not.
    private val WRITE_TRIES = 3

    private var adapter: NfcAdapter? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var nfcState: TextView
    private lateinit var live: TextView
    private lateinit var info: TextView
    private lateinit var log: TextView
    private lateinit var imgStats: TextView
    private lateinit var progress: ProgressBar
    private lateinit var urlField: EditText

    /**
     * The PIN this phone offers to locked tags.
     *
     * Kept in the ordinary preferences and nowhere cleverer. It is not a
     * secret worth protecting on this side: the tag forgets it on a reboot,
     * so anyone holding the tag can clear it anyway, and a PIN the app cannot
     * remember would mean typing it again for every tap of a transfer.
     */
    private lateinit var textField: EditText
    private lateinit var widthField: EditText
    private lateinit var heightField: EditText
    private lateinit var preview: PanelPreviewView

    private lateinit var zoomBar: SeekBar
    private lateinit var rotBar: SeekBar
    private lateinit var brightBar: SeekBar
    private lateinit var contrastBar: SeekBar
    private lateinit var thresholdBar: SeekBar
    private lateinit var lblZoom: TextView
    private lateinit var lblRot: TextView
    private lateinit var lblBright: TextView
    private lateinit var lblContrast: TextView
    private lateinit var lblThreshold: TextView

    private val opt = ImageOptions()
    private var picked: Bitmap? = null

    /**
     * Set when the picker was opened by the upload button rather than by the
     * pick button, so the upload arms itself once an image is in. Pressing
     * "upload" is a statement of intent; losing it because there happened to
     * be nothing to upload yet means pressing it again for no reason.
     */
    private var armAfterPick = false

    /**
     * The picture as the panel will show it, kept between the preview and the
     * packing - which no longer happen together, so that a drag only costs
     * the first of the two.
     */
    @Volatile private var staged: ImagePack.Quantised? = null
    @Volatile private var packed: ImagePack.Result? = null

    /** A firmware image that has passed [FirmwareImage.inspect], and its name. */
    private var firmware: ByteArray? = null
    private var firmwareName = ""


    /** Set while code updates the widgets, so listeners do not fight back. */
    private var syncing = false

    /** The percentage the status line currently shows; see uploadPacked(). */
    @Volatile private var shownPct = -1

    /** Whether reader mode is ours at the moment, and why not when it is not. */
    private var readerOn = false
    private var readerWhy = ""

    /** Section header -> its body, for the collapsible sections. */
    private val sections = LinkedHashMap<TextView, View>()

    /** The last few log lines, mirrored into the header. */
    private val recent = ArrayDeque<String>()

    /** The whole log, newest first and bounded; see [addLog]. */
    private val lines = ArrayDeque<String>()

    /**
     * What to do on the next tap. Null means "just read the tag".
     *
     * It stays armed until it has actually worked, which is the whole point.
     * An upload is seconds of continuous contact with a phone held against a
     * tag by hand - it slides, it lifts, the field drops - and the old
     * behaviour was to disarm on the first tap, succeed or not, so a slip put
     * the app back to plain reading and the next tap read the tag instead of
     * finishing the upload. Now the tap that failed costs an attempt and
     * nothing else: hold the phone back on and it goes again.
     *
     * [attempt] counts only the taps that got *nowhere*, and any tap that
     * moves the transfer forward puts it back to zero. A firmware image is
     * 138 kB over a link that does about 1.3 kB/s: a minute and a half of
     * contact, which no hand provides in one go, so the question is never how
     * many taps it has taken but whether the taps are still achieving
     * something. A budget of attempts gives up on a transfer that is working
     * its way through; a budget of fruitless attempts gives up only on one
     * that is actually stuck, and Cancel is there for the rest.
     */
    // Written on the main thread, read on the binder thread that
    // onTagDiscovered runs on, so the attempt count an action sees is the one
    // the status bar is showing rather than whatever was cached.
    @Volatile private var armed: Action? = null
    @Volatile private var armedName: String = ""
    @Volatile private var armedTries: Int = 1
    @Volatile private var attempt: Int = 0

    /** The furthest this arming has got, and whether this tap added to it. */
    @Volatile private var bestSent: Int = 0
    @Volatile private var bestTotal: Int = 0
    @Volatile private var movedThisTap: Boolean = false
    private lateinit var cancel: Button

    private fun interface Action {
        fun run(nfc: OeplNfc)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        nfcState = findViewById(R.id.nfcState)
        live = findViewById(R.id.live)
        info = findViewById(R.id.info)
        log = findViewById(R.id.log)
        imgStats = findViewById(R.id.imgStats)
        progress = findViewById(R.id.progress)
        urlField = findViewById(R.id.url)
        textField = findViewById(R.id.text)
        widthField = findViewById(R.id.width)
        heightField = findViewById(R.id.height)
        preview = findViewById(R.id.preview)

        zoomBar = findViewById(R.id.zoom)
        rotBar = findViewById(R.id.rot)
        brightBar = findViewById(R.id.bright)
        contrastBar = findViewById(R.id.contrast)
        thresholdBar = findViewById(R.id.threshold)
        lblZoom = findViewById(R.id.lblZoom)
        lblRot = findViewById(R.id.lblRot)
        lblBright = findViewById(R.id.lblBright)
        lblContrast = findViewById(R.id.lblContrast)
        lblThreshold = findViewById(R.id.lblThreshold)

        cancel = findViewById(R.id.btnCancel)
        cancel.setOnClickListener { disarm(getString(R.string.cancelled)) }

        setupSections()
        // Tapping the NFC line asks for reader mode again, for the case where
        // the automatic attempts all came at a bad moment.
        nfcState.setOnClickListener { armReader(manual = true) }

        preview.options = opt
        preview.onChanged = { syncControls(); rerender() }

        setupImageControls()
        setupTagActions()
        syncControls()
        showNfcState()
    }

    // --- sections -----------------------------------------------------------

    /**
     * Each header shows and hides its body. Only the two you reach for
     * constantly start open; the rest used to be several screens of scrolling
     * between you and the thing you wanted.
     */
    private fun setupSections() {
        val heads = listOf(
            R.id.headTag to R.id.secTag,
            R.id.headImage to R.id.secImage,
            R.id.headImageMore to R.id.secImageMore,
            R.id.headNdef to R.id.secNdef,
            R.id.headFirmware to R.id.secFirmware,
            R.id.headCommands to R.id.secCommands,
            R.id.headLog to R.id.secLog
        )
        val open = setOf(R.id.headTag, R.id.headImage)
        for ((h, b) in heads) {
            val head: TextView = findViewById(h)
            val body: View = findViewById(b)
            sections[head] = body
            setSection(head, body, h in open)
            head.setOnClickListener {
                setSection(head, body, body.visibility != View.VISIBLE)
            }
        }
    }

    private fun setSection(head: TextView, body: View, show: Boolean) {
        body.visibility = if (show) View.VISIBLE else View.GONE
        val title = head.text.toString().removePrefix("▾ ").removePrefix("▸ ")
        head.text = (if (show) "▾ " else "▸ ") + title
    }

    /** Open a section from code, e.g. to put a result where it can be seen. */
    private fun openSection(headId: Int) {
        val head: TextView = findViewById(headId)
        sections[head]?.let { setSection(head, it, true) }
    }

    // --- image controls -----------------------------------------------------

    private fun setupImageControls() {
        val ditherSpinner: Spinner = findViewById(R.id.dither)
        ditherSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Dither.values()
        )
        ditherSpinner.setSelection(Dither.values().indexOf(opt.dither))
        ditherSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (syncing) return
                opt.dither = Dither.values()[pos]
                rerender()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val fitSpinner: Spinner = findViewById(R.id.fit)
        fitSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Fit.values()
        )
        fitSpinner.setSelection(Fit.values().indexOf(opt.fit))
        fitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (syncing) return
                opt.fit = Fit.values()[pos]
                rerender()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Zoom is logarithmic, so the useful range (roughly 0.1x to 10x of the
        // fit scale) is reachable without the slider being useless at one end.
        seek(zoomBar) { v ->
            opt.scale = Math.pow(10.0, (v / 1000.0) * 2.0 - 1.0).toFloat()
        }
        seek(rotBar) { v -> opt.rotationDeg = v.toFloat() }
        seek(brightBar) { v -> opt.brightness = v - 100 }
        seek(contrastBar) { v -> opt.contrast = v - 100 }
        seek(thresholdBar) { v -> opt.threshold = v }

        check(R.id.red) { on -> opt.red = on }
        check(R.id.yellow) { on -> opt.yellow = on }
        check(R.id.invert) { on -> opt.invert = on }
        check(R.id.flipH) { on -> opt.flipH = on }
        check(R.id.flipV) { on -> opt.flipV = on }
        check(R.id.blackBg) { on -> opt.background = if (on) ImagePack.BLACK else ImagePack.WHITE }
        check(R.id.compress) { on -> opt.compress = on }

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.btnRotL).setOnClickListener {
            opt.rotationDeg = (opt.rotationDeg - 90f + 360f) % 360f
            syncControls(); rerender()
        }
        findViewById<Button>(R.id.btnRotR).setOnClickListener {
            opt.rotationDeg = (opt.rotationDeg + 90f) % 360f
            syncControls(); rerender()
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            opt.resetTransform()
            opt.resetLevels()
            syncControls(); rerender()
        }

        // Re-render as the panel size is typed, so a wrong size shows up in the
        // preview immediately instead of only after the field loses focus.
        for (f in listOf(widthField, heightField)) {
            f.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { if (!syncing) rerender() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
    }

    private fun seek(bar: SeekBar, apply: (Int) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                apply(v)
                updateLabels()
                rerender()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun check(id: Int, apply: (Boolean) -> Unit) {
        findViewById<CheckBox>(id).setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (syncing) return@setOnCheckedChangeListener
            apply(on)
            rerender()
        }
    }

    /** Push the option values back into the widgets after a gesture or reset. */
    private fun syncControls() {
        syncing = true
        zoomBar.progress = (((Math.log10(opt.scale.toDouble()) + 1.0) / 2.0) * 1000).toInt()
            .coerceIn(0, 1000)
        rotBar.progress = opt.rotationDeg.toInt().coerceIn(0, 359)
        brightBar.progress = (opt.brightness + 100).coerceIn(0, 200)
        contrastBar.progress = (opt.contrast + 100).coerceIn(0, 200)
        thresholdBar.progress = opt.threshold.coerceIn(0, 255)
        findViewById<CheckBox>(R.id.red).isChecked = opt.red
        findViewById<CheckBox>(R.id.yellow).isChecked = opt.yellow
        findViewById<CheckBox>(R.id.invert).isChecked = opt.invert
        findViewById<CheckBox>(R.id.flipH).isChecked = opt.flipH
        findViewById<CheckBox>(R.id.flipV).isChecked = opt.flipV
        findViewById<CheckBox>(R.id.compress).isChecked = opt.compress
        findViewById<Spinner>(R.id.dither).setSelection(Dither.values().indexOf(opt.dither))
        findViewById<Spinner>(R.id.fit).setSelection(Fit.values().indexOf(opt.fit))
        syncing = false
        updateLabels()
    }

    private fun updateLabels() {
        lblZoom.text = "Zoom  %.0f%%".format(opt.scale * 100)
        lblRot.text = "Rotation  %.0f°".format(opt.rotationDeg)
        lblBright.text = "Brightness  %+d".format(opt.brightness)
        lblContrast.text = "Contrast  %+d".format(opt.contrast)
        lblThreshold.text = "Threshold  ${opt.threshold}" +
            if (opt.dither == Dither.NONE) "" else "  (bias)"
    }

    private fun panelSize(): Pair<Int, Int>? {
        val w = widthField.text.toString().toIntOrNull() ?: 0
        val h = heightField.text.toString().toIntOrNull() ?: 0
        if (w !in 1..4000 || h !in 1..4000) return null
        return Pair(w, h)
    }

    /**
     * Re-quantise and redraw. Done synchronously: a 200x152 panel is 30k pixels
     * and even the error-diffusion modes finish well inside a frame, so there
     * is nothing to gain from a worker thread and a stale-result problem to be
     * had from one.
     */
    private fun rerender() {
        val src = picked
        if (src == null) {
            imgStats.text = "No image picked yet."
            return
        }
        val size = panelSize()
        if (size == null) {
            // Never fail silently here: an empty preview after picking a photo
            // reads as "the picker is broken" rather than "the panel size is
            // missing", which is exactly the wrong conclusion.
            preview.setPreview(null, 0, 0)
            imgStats.text = "Set a panel width and height between 1 and 4000 " +
                "(or read the tag) to see the preview."
            main.removeCallbacks(packJob)
            staged = null
            packed = null
            return
        }
        val (w, h) = size
        // Only as far as the picture. Packing - and above all compressing -
        // would be done again on the next touch event and thrown away, so it
        // waits for the gesture to stop; see packSoon().
        val q = ImagePack.quantised(src, opt, w, h)
        staged = q
        packed = null
        preview.setPreview(q.preview, w, h)
        imgStats.text = "%d x %d  ->  %d bytes raw%s".format(
            w, h, q.rawSize, if (opt.compress) ", compressing..." else "")
        packSoon()
        armUploadIfIdle()
    }

    /**
     * Work out the bytes once the picture has stopped moving.
     *
     * Deflating eleven kilobytes is not much on its own; doing it for every
     * sample of a drag, on the thread that is also drawing the drag, is what
     * made the app feel heavy and left a queue of work behind that the window
     * had to grind through before it could close. So each change cancels the
     * previous one and only the last gets packed - and if a tap beats the
     * timer to it, uploadImage() packs for itself anyway.
     */
    private fun packSoon() {
        main.removeCallbacks(packJob)
        main.postDelayed(packJob, PACK_DELAY_MS)
    }

    private val packJob = Runnable {
        val q = staged ?: return@Runnable
        val src = picked ?: return@Runnable
        val r = ImagePack.packQuantised(q)
        packed = r
        showStats(r, q.w, q.h, src)
    }

    /**
     * Arm the upload because the picture changed.
     *
     * Zooming, dragging, rotating, dithering differently - every one of those
     * is someone getting a picture ready to send, and making them reach for
     * the Upload button afterwards only adds a step that can be forgotten
     * between fiddling and tapping the tag. So the act of editing is the arm.
     *
     * Not if something else is already waiting, though: a firmware update, an
     * NDEF write or an upload that is part way through a multi-tap transfer
     * all outrank this, and quietly replacing one of those with an image
     * upload because a slider moved would be the worst kind of surprise. A
     * bare "Read tag" is not in that category - it is what a tap does when
     * nothing is armed at all, so it is not something waiting.
     */
    private fun armUploadIfIdle() {
        if (armed != null) return
        arm("Upload image", UPLOAD_TRIES) { nfc -> uploadImage(nfc) }
    }

    private fun showStats(r: ImagePack.Result, w: Int, h: Int, src: Bitmap) {
        imgStats.text = if (r.compressed)
            "%d x %d  ->  %d bytes compressed from %d (%d%%), type 0x%02X".format(
                w, h, r.data.size, r.rawSize,
                if (r.rawSize > 0) r.data.size * 100 / r.rawSize else 100, r.dataType
            )
        else
            "%d x %d  ->  %d bytes, type 0x%02X  (source %d x %d)".format(
                w, h, r.data.size, r.dataType, src.width, src.height
            )
    }

    // --- tag actions --------------------------------------------------------

    private fun setupTagActions() {
        findViewById<Button>(R.id.btnRead).setOnClickListener { arm("Read tag", action = null) }

        findViewById<Button>(R.id.btnSetUrl).setOnClickListener {
            val url = urlField.text.toString().trim()
            if (url.isEmpty()) {
                addLog("Enter a URL first.")
                return@setOnClickListener
            }
            arm("Write URL") { nfc -> writeNdefConfig(nfc, NdefCodec.uriMessage(url), "URL $url") }
        }

        findViewById<Button>(R.id.btnSetText).setOnClickListener {
            val text = textField.text.toString()
            if (text.isEmpty()) {
                addLog("Enter a text first.")
                return@setOnClickListener
            }
            arm("Write text") { nfc -> writeNdefConfig(nfc, NdefCodec.textMessage(text), "text") }
        }

        findViewById<Button>(R.id.btnClearNdef).setOnClickListener {
            arm("Clear NDEF") { nfc ->
                nfc.command(Cmd.CLEAR_NDEF)
                post { addLog("NDEF configuration cleared, back to the built-in default.") }
            }
        }

        findViewById<Button>(R.id.btnUpload).setOnClickListener {
            rerender()
            if (picked == null) {
                // Nothing to upload yet, so open the picker rather than
                // explaining that there is nothing to upload. The next thing
                // the user was going to do is the thing this button can do.
                armAfterPick = true
                pickImage()
                return@setOnClickListener
            }
            // staged, not packed: the bytes may still be waiting on the pack
            // timer, and that is not the same as having nothing to send.
            if (staged == null) {
                addLog("Set a sensible panel size first.")
                return@setOnClickListener
            }
            // Deliberately not the packed bytes: the geometry is settled
            // against the tag on the tap, not now.
            arm("Upload image", UPLOAD_TRIES) { nfc -> uploadImage(nfc) }
        }

        findViewById<Button>(R.id.btnLed).setOnClickListener {
            arm("Flash LED") { nfc ->
                // A pattern rather than BLE_CMD_LED: that one sets a colour and
                // leaves it there, so it would mean holding the session open
                // for the length of the flash and switching it off again. The
                // pattern engine runs from the tag's own main loop, so this
                // keeps flashing after the phone is gone - which is also what
                // makes it useful for finding a tag. The Commands screen has
                // the full editor.
                val reply = nfc.command(Cmd.SET_LED, LedPattern.confirmation().encode())
                if (reply != null && reply.cmd == Cmd.ERR)
                    throw OeplException("the tag rejected the LED pattern")
                post { addLog("LED: green double flash, twice.") }
            }
        }
        findViewById<Button>(R.id.btnTime).setOnClickListener {
            arm("Set clock") { nfc ->
                // BLE_CMD_SET_UNIXTIME takes a local-time stamp as four little
                // endian bytes plus a clock mode byte, which the handler reads
                // unconditionally - so all five have to be there.
                val now = System.currentTimeMillis()
                val t = now / 1000 + java.util.TimeZone.getDefault().getOffset(now) / 1000
                nfc.command(
                    Cmd.SET_UNIXTIME,
                    byteArrayOf(
                        t.toByte(), (t shr 8).toByte(),
                        (t shr 16).toByte(), (t shr 24).toByte(),
                        1
                    )
                )
                post { addLog("Clock set to ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}.") }
            }
        }
        findViewById<Button>(R.id.btnReboot).setOnClickListener {
            arm("Reboot", 1) { nfc -> nfc.command(Cmd.REBOOT) }
        }

        findViewById<Button>(R.id.btnFirmware).setOnClickListener { pickFirmware() }
        findViewById<Button>(R.id.btnFlashFirmware).setOnClickListener {
            val fw = firmware
            if (fw == null) {
                addLog("Pick a firmware .bin first.")
                return@setOnClickListener
            }
            confirmFirmware(fw)
        }

        findViewById<Button>(R.id.btnAllCommands).setOnClickListener {
            CommandActivity.start(this)
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener { log.text = "" }
    }

    // --- reader mode --------------------------------------------------------

    /**
     * Take reader mode, and keep taking it.
     *
     * Reader mode rather than the foreground dispatch: we want ISO-DEP in our
     * own hands, no platform NDEF probing in front of it, and no system chirp
     * on every one of the hundreds of taps a session is.
     *
     * Getting it back afterwards is the part that was wrong. It was claimed in
     * onResume and dropped in onPause, which holds for a plain home-and-back,
     * but not for a window that is made small or moved into split screen:
     * there the activity is visible and being used while *paused*, so reader
     * mode had been dropped and never asked for again - and from then on the
     * platform handled the taps instead, which is what "the app stopped seeing
     * the tag" looked like.
     *
     * So it is claimed on every way back in - start, resume and window focus -
     * dropped only in onStop when the activity really is away, and the adapter
     * is looked up each time because NFC can be switched off and on underneath
     * us.
     */
    private fun armReader(manual: Boolean = false) {
        if (Build.VERSION.SDK_INT < 19) return
        adapter = NfcAdapter.getDefaultAdapter(this)
        val a = adapter
        if (a == null) {
            readerOn = false
            readerWhy = "this phone has no NFC"
        } else if (!a.isEnabled) {
            readerOn = false
            readerWhy = "NFC is switched off in Android settings"
        } else {
            try {
                a.enableReaderMode(
                    this, this,
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null
                )
                if (!readerOn && manual) addLog("Reader mode taken back.")
                readerOn = true
                readerWhy = ""
            } catch (e: Exception) {
                // Android refuses this from an activity it does not consider
                // resumed. Not fatal and not final: the next focus or resume
                // tries again.
                readerOn = false
                readerWhy = e.javaClass.simpleName
            }
        }
        showNfcState()
    }

    private fun showNfcState() {
        if (!this::nfcState.isInitialized) return
        nfcState.text = if (readerOn)
            "NFC reader active — hold the phone against the tag"
        else
            "NFC reader NOT active" +
                (if (readerWhy.isEmpty()) "" else " ($readerWhy)") +
                " — tap here to retry"
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
        // The one that catches the small-window case: there is no onResume
        // when a visible-but-paused window is touched, but there is a focus
        // change.
        if (hasFocus) armReader()
    }

    override fun onStop() {
        super.onStop()
        // Not onPause: a paused activity can still be the one in front of the
        // user, and dropping reader mode there is the bug this replaces.
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                adapter?.disableReaderMode(this)
            } catch (_: Exception) {
            }
        }
        readerOn = false
        readerWhy = "the app was in the background"
        // Nothing queued here is worth delaying the way out: whatever was
        // about to be packed or logged can be done again on the way back in.
        main.removeCallbacks(packJob)
        main.removeCallbacks(logJob)
    }

    override fun onTagDiscovered(tag: Tag) {
        val iso = IsoDep.get(tag)
        if (iso == null) {
            post { addLog("That tag does not speak ISO-DEP.") }
            return
        }
        val action = armed
        val name = if (action == null) "Read tag" else armedName
        val tries = armedTries
        val n = if (action == null) 1 else ++attempt
        movedThisTap = false
        post {
            status.text = if (action == null || tries <= 1)
                getString(R.string.working, name)
            else getString(R.string.working_attempt, name, n, tries)
            // Not back to zero: a resumed transfer starts where the last one
            // stopped, and a bar that drops to nothing on every tap says the
            // opposite of what is happening.
            progress.progress = percentDone()
        }

        val nfc = OeplNfc(iso)
        try {
            nfc.open()
            nfc.selectApplication()
            try {
                nfc.readCapabilities()
            } catch (e: Exception) {
                // Not fatal: we fall back to the sizes the firmware advertises
                // by default and carry on.
                post { addLog("Capability container unreadable: ${e.message}") }
            }
            // No unlock. The PIN guards BLE, never NFC -
            // a tag that should not take commands from a passing phone has
            // its NFC command channel switched off instead, and only BLE can
            // switch it back on. See the note where unlockIfNeeded() used to
            // be in OeplNfc.kt.
            // As many goes as this one tap will give us.
            //
            // A failure used to end the tap, and the next attempt needed the
            // phone lifted and put down again. On a reader whose ISO-DEP
            // session dies early that made a transfer impossible rather than
            // slow: every attempt lost the block in flight, and nothing
            // accumulated however many times it was tried.
            //
            // Two things make retrying in place worth it now. The tag keeps
            // the parts that arrived, so a second go carries on from where
            // the first stopped instead of starting the block again. And the
            // phone is already lying on the tag - the one thing the transfer
            // needs is contact, and lifting it to retry throws away the only
            // resource there is.
            //
            // Bounded by both a count and a clock, because this runs on the
            // NFC dispatch thread and no further tap can be delivered until
            // it returns. Cancel breaks it too: armed goes null.
            var lastError: Exception? = null
            var done = false
            val until = System.currentTimeMillis() + IN_TAP_MS
            var goes = 0
            while (true) {
                goes++
                try {
                    if (action == null) readTag(nfc) else action.run(nfc)
                    done = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    if (action == null || goes >= IN_TAP_GOES ||
                        System.currentTimeMillis() > until || armed == null)
                        break
                    val why = "${e.javaClass.simpleName}: ${e.message}"
                    post {
                        addLog("go $goes: $why - carrying on in the same tap")
                        status.text = getString(
                            R.string.retry_progress, name, percentDone())
                    }
                    // Re-open the link and select the application again. A
                    // failure here means the tag really has gone, and then
                    // there is nothing to carry on with.
                    if (!nfc.recover()) break
                }
            }
            if (!done) throw (lastError
                ?: OeplException("$name: nothing to report"))
            post {
                clearArmed()
                status.text = getString(R.string.done, name)
                progress.progress = 100
            }
        } catch (e: OeplTagLost) {
            // Not a failure of the transfer, just the end of this tap. Say so
            // in one line and get out of the way: this runs on the NFC
            // dispatch thread, and the next tap cannot be delivered until it
            // returns. The attempt is not counted either - a tap that was
            // taken away is not an attempt that was used up.
            val moved = movedThisTap
            if (moved) attempt = 0 else attempt = (attempt - 1).coerceAtLeast(0)
            post {
                status.text = getString(R.string.tag_left, name)
                progress.progress = percentDone()
            }
        } catch (e: Exception) {
            val moved = movedThisTap
            if (moved) attempt = 0
            post {
                addLog("${e.javaClass.simpleName}: ${e.message}")
                when {
                    action == null -> status.text = getString(R.string.failed, name)
                    // This tap got further than any before it, so the transfer
                    // is working - it just needs more contact than a hand
                    // gives in one go. That is not an attempt worth counting.
                    moved -> status.text =
                        getString(R.string.retry_progress, name, percentDone())
                    n >= tries -> {
                        clearArmed()
                        status.text = getString(R.string.gave_up, name, n)
                    }
                    else -> status.text = getString(R.string.retry, name, n, tries)
                }
            }
        } finally {
            nfc.close()
        }
    }

    /**
     * Note how far a transfer has got.
     *
     * Called from the upload loops' progress callback, so "forward" means what
     * the tag has actually taken: the tag decides which block it wants next,
     * and a resumed transfer therefore reports a figure that already includes
     * the blocks it kept from earlier taps.
     */
    private fun noteProgress(sent: Int, total: Int) {
        bestTotal = total
        if (sent > bestSent) {
            bestSent = sent
            movedThisTap = true
        }
    }

    private fun percentDone(): Int {
        val t = bestTotal
        return if (t > 0) (bestSent.toLong() * 100 / t).toInt().coerceIn(0, 100) else 0
    }

    /**
     * Arm an action. [tries] is how many *fruitless* taps it may cost before
     * the app gives up on its own; a tap that gets the transfer further resets
     * the count, so a long upload is limited by whether it is still moving
     * rather than by how long it takes.
     */
    private fun arm(name: String, tries: Int = 3, action: Action?) {
        armed = action
        armedName = name
        armedTries = if (action == null) 1 else tries
        attempt = 0
        bestSent = 0
        bestTotal = 0
        movedThisTap = false
        cancel.visibility = if (action == null) View.GONE else View.VISIBLE
        status.text = getString(R.string.armed, name)
    }

    private fun clearArmed() {
        armed = null
        armedName = ""
        armedTries = 1
        attempt = 0
        bestSent = 0
        bestTotal = 0
        cancel.visibility = View.GONE
    }

    private fun disarm(why: String) {
        clearArmed()
        status.text = why
    }

    private fun readTag(nfc: OeplNfc) {
        val ndef = nfc.readNdef()
        val described = NdefCodec.describe(ndef)
        // The settings are the one part of a read that comes back in numbered
        // pieces - 176 bytes of them - so it is the one part a single lost
        // frame can spoil. Asked again rather than given up on: the command
        // only reads, so running it twice costs a round trip and nothing
        // else, and one more try is the difference between a tap that shows
        // the panel configuration and a tap that shows nothing.
        //
        // And when it does fail, the reason goes where it can be seen. It
        // used to go to the log alone, which left the pane empty with no
        // statement of what went wrong - the exceptions underneath name the
        // piece and the status word, and that is exactly what is needed to
        // tell a lost frame from a refusal.
        var tagInfo: TagInfo? = null
        var why: String? = null
        for (attempt in 0 until 3) {
            try {
                tagInfo = TagInfo.parse(nfc.commandExpecting(Cmd.GET_INFOS))
                why = null
                break
            } catch (e: Exception) {
                why = e.message ?: e.javaClass.simpleName
                post { addLog("Settings read ${attempt + 1}/3 failed: $why") }
            }
        }
        val reason = why
        post {
            val sb = StringBuilder()
            if (reason != null) {
                sb.append("Settings  unreadable (").append(reason).append(")\n")
            }
            if (tagInfo != null) {
                sb.append("MAC      ").append(tagInfo.mac).append('\n')
                sb.append("Name     ").append(tagInfo.name).append('\n')
                sb.append("HW type  ").append("0x%04X".format(tagInfo.hwType)).append('\n')
                sb.append("Panel    ")
                    .append("${tagInfo.width} x ${tagInfo.height}, ")
                    .append("rot ${tagInfo.guiRotation}")
                    .append('\n')
                sb.append("Type     ").append(tagInfo.panelLabel).append('\n')
                sb.append("OEPL     ")
                    .append(if (tagInfo.oeplEnabled) "enabled" else "disabled").append('\n')
                // Prefill the image fields so an upload matches the panel.
                widthField.setText(tagInfo.width.toString())
                heightField.setText(tagInfo.height.toString())
                adoptColours(tagInfo)
                syncControls()
                rerender()
            }
            sb.append("NDEF     ").append(described)
            if (nfc.maxNdefSize > 0) sb.append(" (room for ${nfc.maxNdefSize - 2} bytes)")
            info.text = sb.toString()
            addLog("Read: $described")
        }
    }

    /**
     * Take the panel's colours from the tag.
     *
     * Returns true if anything changed, so the caller knows whether the image
     * it already packed is still the right one. The user's own choice is not
     * sacred here: a red plane on a yellow panel comes out yellow and a second
     * plane on a one-colour panel is bytes the tag throws away, so what the
     * tag says wins. Both directions, which is the part that was missing: a
     * panel that *has* a second colour gets one added, not just taken away.
     */
    private fun adoptColours(info: TagInfo): Boolean {
        val wantRed = info.second == Second.RED || info.second == Second.BOTH
        val wantYellow = info.second == Second.YELLOW || info.second == Second.BOTH
        if (opt.red == wantRed && opt.yellow == wantYellow) return false
        opt.red = wantRed
        opt.yellow = wantYellow
        return true
    }

    /**
     * Write an NDEF configuration.
     *
     * Always as the access point does it: the record wrapped in a Type-2
     * TLV and pushed as an OEPL block transfer with dataType
     * DATATYPE_NFC_RAW_CONTENT.
     *
     * That is the one way the firmware accepts now. The direct command
     * (BLE_CMD_SET_NDEF) is withdrawn, and it was withdrawn for a reason this
     * function used to work around by hand: it carried no length agreement
     * and no checksum, so a mangled write was stored mangled and answered
     * "saved", and the only defence was to read the message back and compare
     * - which could be mangled in turn.
     *
     * The block transfer announces a length and a CRC32, is acknowledged part
     * by part and is validated before a byte reaches flash. So the read-back
     * loop is gone with it: the transfer either completes or says it did not.
     */
    private fun writeNdefConfig(nfc: OeplNfc, message: ByteArray, what: String) {
        val tlv = NdefCodec.toType2Tlv(message)
        uploadData(
            nfc, tlv, DataType.NFC_RAW_CONTENT,
            onLog = { line -> post { addLog(line) } }
        )
        post {
            addLog("Wrote $what as an AP-format TLV (${tlv.size} bytes).")
            addLog("Sent: " + NdefCodec.describe(message))
        }
    }

    /**
     * Ask before a firmware update, and say what it will cost in time.
     *
     * Not caution for its own sake: this is minutes of holding a phone against
     * a tag, and an attempt abandoned halfway leaves a partial image in the
     * update area - harmless, because the tag checks the whole thing before
     * copying it over itself, but a wasted few minutes. Worth one tap.
     */
    private fun confirmFirmware(fw: ByteArray) {
        // A phone turns a frame around in a millisecond or two where the bench
        // reader takes eight, so this is the pessimistic end of what has
        // actually been measured rather than a guess.
        val secs = fw.size / 1200
        AlertDialog.Builder(this)
            .setTitle("Flash firmware?")
            .setMessage(
                ("%s\n%d bytes, about %d:%02d of continuous NFC contact.\n\n" +
                    "The tag checks the image's marker, length and CRC before it " +
                    "copies it over its own firmware, and reboots into it. Hold " +
                    "the phone still the whole way through.")
                    .format(firmwareName, fw.size, secs / 60, secs % 60)
            )
            .setPositiveButton("Flash") { _, _ ->
                arm("Firmware update", FIRMWARE_TRIES) { nfc -> uploadFirmware(nfc, fw) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadFirmware(nfc: OeplNfc, fw: ByteArray) {
        post { addLog("Firmware update: %d bytes".format(fw.size)) }
        val started = System.currentTimeMillis()
        shownPct = -1
        uploadData(
            nfc, fw, DataType.FW_UPDATE,
            onProgress = { sent, total ->
                noteProgress(sent, total)
                val pct = if (total > 0) sent * 100 / total else 0
                // One update per percent, not one per part. A firmware image
                // is six hundred parts, and six hundred posts that each set a
                // progress bar and a string is a queue the main thread has to
                // work through before it can do anything else - including
                // closing the window.
                if (pct != shownPct) {
                    shownPct = pct
                    post {
                        progress.progress = pct
                        status.text = getString(R.string.uploading, pct, sent, total)
                    }
                }
            },
            onLog = { line -> post { addLog(line) } }
        )
        val secs = (System.currentTimeMillis() - started) / 1000.0
        post {
            addLog(("Firmware sent in %.1f s (%.0f B/s). The tag verifies it, " +
                "writes it over its own firmware and reboots - a few more seconds " +
                "during which it must not lose power.").format(secs, fw.size / secs))
        }
    }

    /**
     * Upload the picked image, packed for the panel it is actually going to.
     *
     * The panel size is a text field, and a wrong one is easy to end up with -
     * a different tag was read last, or it was never read at all. Packing to
     * it produces an image that is cropped, letterboxed or simply the wrong
     * shape, and the first you know is a tag showing it. The tag knows its own
     * geometry, and by this point we are talking to it, so ask: if it
     * disagrees with what the preview was built from, pack again against the
     * answer before sending anything.
     *
     * The colours get the same treatment in one direction only: a second plane
     * for a panel that has no second colour is bytes that can only be wrong,
     * so it is dropped. A panel that *has* one is left to the user, who may
     * well want a black and white picture on it.
     */
    private fun uploadImage(nfc: OeplNfc) {
        if (attempt > 1) post {
            // The tag decides where to pick up, not the app: it answers the
            // AVAILDATA with the block it wants next. Whole blocks it already
            // holds are not sent again; the one that was in flight when the
            // phone slipped is. A tag on firmware older than this resume
            // support answers block 0 again, and uploadData() says so.
            addLog("Attempt $attempt: asking the tag where to carry on.")
        }
        val src = picked ?: throw OeplException("no image picked")
        val want = panelSize() ?: throw OeplException("no sensible panel size")
        val info = TagInfo.parse(nfc.commandExpecting(Cmd.GET_INFOS))
        val tagW = info.width
        val tagH = info.height

        var p = packed
        val geometryWrong = tagW in 1..4000 && tagH in 1..4000 &&
            (tagW != want.first || tagH != want.second)
        val coloursWrong = adoptColours(info)
        if (geometryWrong || coloursWrong || p == null) {
            if (geometryWrong) post {
                addLog("The tag is %dx%d, the image was packed for %dx%d - packing again."
                    .format(tagW, tagH, want.first, want.second))
            }
            if (coloursWrong) post {
                addLog("The tag is ${info.panelLabel} - packing for those colours.")
            }
            val w = if (geometryWrong) tagW else want.first
            val h = if (geometryWrong) tagH else want.second
            // The usual reason p is null here is simply that the pack timer
            // has not fired yet - the phone met the tag within a fifth of a
            // second of the last touch. The picture is already quantised in
            // that case, so pack that rather than rendering it a second time.
            val q = staged
            p = if (!geometryWrong && !coloursWrong && q != null && q.matches(opt, w, h))
                ImagePack.packQuantised(q)
            else
                ImagePack.process(src, opt, w, h)
            packed = p
            post {
                syncing = true
                widthField.setText(w.toString())
                heightField.setText(h.toString())
                syncing = false
                syncControls()
                preview.setPreview(p.preview, w, h)
                showStats(p, w, h, src)
            }
        }
        uploadPacked(nfc, p)
    }

    private fun uploadPacked(nfc: OeplNfc, p: ImagePack.Result) {
        post {
            addLog("Uploading %d bytes, type 0x%02X".format(p.data.size, p.dataType))
        }
        val started = System.currentTimeMillis()
        uploadData(
            nfc, p.data, p.dataType,
            onProgress = { sent, total ->
                noteProgress(sent, total)
                val pct = if (total > 0) sent * 100 / total else 0
                // One update per percent, not one per part. A firmware image
                // is six hundred parts, and six hundred posts that each set a
                // progress bar and a string is a queue the main thread has to
                // work through before it can do anything else - including
                // closing the window.
                if (pct != shownPct) {
                    shownPct = pct
                    post {
                        progress.progress = pct
                        status.text = getString(R.string.uploading, pct, sent, total)
                    }
                }
            },
            onLog = { line -> post { addLog(line) } }
        )
        val secs = (System.currentTimeMillis() - started) / 1000.0
        post {
            addLog("Upload took %.1f s. Keep the phone still until the panel refreshes.".format(secs))
        }
    }

    // --- plumbing -----------------------------------------------------------

    // One image picker does not justify pulling in the activity-result library.
    @Suppress("DEPRECATION")
    private fun pickImage() {
        // The system photo picker, asked for by name, so one tap picks.
        //
        // ACTION_GET_CONTENT is routed by the device and lands wherever the
        // vendor wants it. On the phones this app is used on that is the
        // vendor gallery, which presents an image grid with checkboxes and a
        // confirm button - so choosing one picture costs a tap to select and
        // another to confirm, and it invites picking several when only one
        // can be used.
        //
        // ACTION_PICK_IMAGES is the photo picker itself rather than a
        // suggestion, and without EXTRA_PICK_IMAGES_MAX it is single-select:
        // a tap on an image returns it and closes. Android 13 and up only,
        // hence the fallbacks below, which are what every device used before
        // and still what a stripped build gets.
        if (Build.VERSION.SDK_INT >= 33) {
            val pick = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
            try {
                startActivityForResult(pick, REQ_IMAGE)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        // ACTION_GET_CONTENT goes to the system photo picker on Android 13+ and
        // to the documents UI before that. If a device has neither - some
        // stripped builds do not - fall back to OPEN_DOCUMENT rather than
        // throwing ActivityNotFoundException out of the click listener.
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(get, REQ_IMAGE)
            return
        } catch (_: ActivityNotFoundException) {
        }
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(open, REQ_IMAGE)
        } catch (e: ActivityNotFoundException) {
            addLog("This device has no app that can hand over an image.")
        }
    }

    /**
     * Pick a firmware image. Deliberately unfiltered: a .bin has no registered
     * MIME type, so filtering on one hides every candidate file on most
     * devices and the picker comes up empty. What the file actually is gets
     * decided by [FirmwareImage.inspect], which is the same decision the tag
     * makes.
     */
    @Suppress("DEPRECATION")
    private fun pickFirmware() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(open, REQ_FIRMWARE)
            return
        } catch (_: ActivityNotFoundException) {
        }
        val get = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(get, REQ_FIRMWARE)
        } catch (_: ActivityNotFoundException) {
            addLog("This device has no app that can hand over a file.")
        }
    }

    /** Read the picked file, and keep it only if the tag would accept it. */
    private fun loadFirmware(uri: Uri) {
        firmware = null
        firmwareName = displayName(uri)
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Throwable) {
            addLog("Could not read that file: ${e.javaClass.simpleName} ${e.message ?: ""}")
            return
        }
        if (bytes == null) {
            addLog("The picker returned nothing readable.")
            return
        }
        // Checked before inspect() reads anything out of it: a file far larger
        // than the update area is the wrong file, not an oversized firmware.
        if (bytes.size > FirmwareImage.MAX_SIZE) {
            addLog("That file is ${bytes.size} bytes; the tag's update area holds " +
                "${FirmwareImage.MAX_SIZE}.")
            return
        }
        try {
            val info = FirmwareImage.inspect(bytes)
            firmware = bytes
            addLog("Firmware $firmwareName: $info")
        } catch (e: FirmwareImage.Invalid) {
            addLog("Not a usable firmware image: ${e.message}")
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "the file"
            }
        } catch (_: Throwable) {
            // A provider that refuses to be queried still hands over bytes.
        }
        return uri.lastPathSegment ?: "the file"
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) {
            armAfterPick = false
            return
        }
        if (requestCode == REQ_FIRMWARE) {
            val uri = data?.data
            if (uri == null) addLog("The picker returned no file.") else loadFirmware(uri)
            return
        }
        if (requestCode != REQ_IMAGE) return
        val uri = data?.data
        if (uri == null) {
            addLog("The picker returned no image.")
            return
        }
        // Throwable, not Exception: decoding a camera photo is the one place
        // here that can raise OutOfMemoryError, which is an Error.
        try {
            val b = loadBitmap(uri)
            if (b == null) {
                addLog("Could not decode that image.")
                return
            }
            picked = b
            addLog("Loaded image ${b.width} x ${b.height}.")
            opt.resetTransform()
            syncControls()
            rerender()
            // Picking a picture is only ever the first half of sending one,
            // so the upload arms itself - whichever button opened the picker.
            armAfterPick = false
            if (staged == null) addLog("Set a sensible panel size, then upload.")
            else arm("Upload image", UPLOAD_TRIES) { nfc -> uploadImage(nfc) }
        } catch (e: Throwable) {
            armAfterPick = false
            picked = null
            rerender()
            addLog("Could not read that image: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    /**
     * Decode at a sane size and sitting the right way up.
     *
     * A current phone camera produces 12 MP or more; decoded straight to
     * ARGB_8888 that is roughly 48 MB for a single bitmap, which is a real risk
     * against a typical 128-256 MB heap and buys nothing - the largest panel
     * this firmware drives is about 1200 px. So decode the bounds first and let
     * BitmapFactory subsample on the way in.
     *
     * EXIF orientation matters too: phones record portrait shots as landscape
     * plus a rotation flag, and BitmapFactory ignores the flag, so without this
     * every portrait photo arrives on its side.
     */
    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImagePack.sampleSizeFor(
                bounds.outWidth, bounds.outHeight, MAX_SOURCE_EDGE)
        }
        val raw = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        if (opts.inSampleSize > 1)
            addLog("Source ${bounds.outWidth} x ${bounds.outHeight} subsampled by ${opts.inSampleSize}.")

        val degrees = exifRotation(uri)
        if (degrees == 0) return raw
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated != raw) raw.recycle()
        addLog("Applied EXIF rotation ${degrees}°.")
        return rotated
    }

    private fun exifRotation(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Throwable) {
            0   // no EXIF, or an unreadable one: not worth failing the load over
        }
    }

    private fun post(block: () -> Unit) = main.post(block)

    /**
     * Add a line to the log.
     *
     * This used to read the whole TextView back and prepend to it, which made
     * every line cost more than the one before: a transfer logs per block, and
     * after a few hundred lines each entry was re-laying out a TextView with
     * the entire session in it. That is what made the app feel slow the longer
     * it ran, and what the window had to finish before it could close.
     *
     * So the text lives in a bounded list, the three-line header is updated
     * straight away because it is cheap and it is what is being watched, and
     * the full log - which is usually scrolled out of sight - is rebuilt once
     * a burst has passed rather than once per line.
     */
    private fun prefs() = getSharedPreferences("oeplnfc", MODE_PRIVATE)

    private fun addLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addFirst("$stamp  $line")
        while (lines.size > LOG_LINES) lines.removeLast()
        recent.addFirst("$stamp  $line")
        while (recent.size > 3) recent.removeLast()
        live.text = recent.joinToString("\n")
        main.removeCallbacks(logJob)
        main.postDelayed(logJob, LOG_DELAY_MS)
    }

    private val logJob = Runnable { log.text = lines.joinToString("\n") }

    companion object {
        private const val REQ_IMAGE = 1
        private const val REQ_FIRMWARE = 2

        // How many taps that achieved *nothing* an action may cost before
        // the app stops waiting for it. Any tap that gets the transfer further
        // resets the count, so these are not a limit on how long an upload may
        // take - a firmware image legitimately needs a dozen taps - only on how
        // long it may keep failing at the same place.
        private const val UPLOAD_TRIES = 40
        private const val FIRMWARE_TRIES = 80

        /**
         * How many goes one tap may take, and for how long.
         *
         * Generous on purpose: the thing a transfer is short of is contact,
         * and while the phone is on the tag there is no reason to stop
         * trying. Twenty-four goes or ninety seconds, whichever runs out
         * first - long enough for a sixty-two part image to crawl through a
         * link that drops every few hundred frames, short enough that a tap
         * which is getting nowhere hands the dispatch thread back for the
         * next one.
         */
        private const val IN_TAP_GOES = 24
        private const val IN_TAP_MS = 90_000L

        // No panel this firmware drives is anywhere near this wide, so
        // subsampling to it costs no visible quality and keeps a camera
        // photo from needing tens of megabytes of heap.
        private const val MAX_SOURCE_EDGE = 2048

        // Long enough that a drag or a slider sweep produces one piece of
        // work instead of fifty, short enough that letting go and tapping the
        // tag normally finds the bytes already there.
        private const val PACK_DELAY_MS = 180L
        private const val LOG_DELAY_MS = 120L

        // Enough to follow a whole transfer, few enough that rebuilding the
        // view is cheap whatever the session has been through.
        private const val LOG_LINES = 200
    }
}
