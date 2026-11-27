package com.chronoflow.stopwatch

import android.app.Activity
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.content.res.ColorStateList
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private enum class State { IDLE, RUNNING, PAUSED }
    private data class Session(val name: String, val date: String, val total: Long, val laps: List<Long>)

    private val bg = 0xFF080D18.toInt()
    private val panel = 0xFF111C2D.toInt()
    private val panelAlt = 0xFF172438.toInt()
    private val muted = 0xFF91A2B8.toInt()
    private val cyan = 0xFF35D6C5.toInt()
    private val white = 0xFFF4F7FC.toInt()
    private val orange = 0xFFFFC928.toInt()
    private val red = 0xFFFF7185.toInt()

    private var state = State.IDLE
    private var startedAt = 0L
    private var accumulated = 0L
    private var lastLapMark = 0L
    private val laps = mutableListOf<Long>()
    private var history = mutableListOf<Session>()
    private var latestSession: Session? = null
    private var volumeDownLongPressed = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var timerText: TextView
    private lateinit var stateText: TextView
    private lateinit var dateText: TextView
    private lateinit var lapCountText: TextView
    private lateinit var averageText: TextView
    private lateinit var bestText: TextView
    private lateinit var lapList: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button

    private val ticker = object : Runnable {
        override fun run() {
            if (state == State.RUNNING) {
                timerText.text = formatTime(elapsed())
                handler.postDelayed(this, 30)
            }
        }
    }

    private val pdfPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            val session = latestSession
            if (uri != null && session != null) writePdf(uri, session)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        history = loadHistory()
        buildScreen()
        renderHistory()
        updateControls()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
        }

        // Brand header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brandIcon = TextView(this).apply {
            text = "◷"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(bg)
            background = rounded(cyan, 14)
        }
        header.addView(brandIcon, LinearLayout.LayoutParams(dp(42), dp(42)))
        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        brandColumn.addView(label("RMG TIMER", 18f, white, true).apply { letterSpacing = .06f })
        brandColumn.addView(label("PRECISION TIME STUDY", 9f, orange, true).apply { setPadding(0, dp(3), 0, 0); letterSpacing = .12f })
        header.addView(brandColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val today = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
        dateText = label(today, 10f, cyan, true).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(0xFF153039.toInt(), 30) }
        header.addView(dateText)
        content.addView(header)

        // Timer hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(19))
            background = rounded(panel, 25)
        }
        val heroTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stateText = label("READY TO BEGIN", 11f, cyan, true).apply { letterSpacing = .15f }
        heroTop.addView(stateText, LinearLayout.LayoutParams(0, -2, 1f))
        val resetAction = label("↻  RESET", 9f, orange, true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(panelAlt, 20)
            isClickable = true
            setOnClickListener { confirmReset() }
        }
        heroTop.addView(resetAction)
        hero.addView(heroTop)
        timerText = label("00:00:00.00", 31.2f, white, true).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            letterSpacing = -.025f
            setPadding(0, dp(30), 0, dp(8))
        }
        hero.addView(timerText)
        hero.addView(label("HOURS     MINUTES     SECONDS", 9f, muted, false).apply { gravity = Gravity.CENTER; letterSpacing = .08f; setPadding(0, 0, 0, dp(22)) })
        hero.addView(View(this).apply { setBackgroundColor(0xFF26364A.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }
        val statLap = statColumn("LAPS", "0", cyan)
        val statAvg = statColumn("AVERAGE", "—", orange)
        val statBest = statColumn("BEST LAP", "—", white)
        lapCountText = statLap.second
        averageText = statAvg.second
        bestText = statBest.second
        stats.addView(statLap.first, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(statAvg.first, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(statBest.first, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(stats)
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(25) })

        // Exactly two multifunction controls
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(7)) }
        primaryButton = actionButton("START", cyan, bg) { primaryAction() }
        secondaryButton = actionButton("LAP", 0xFF24334A.toInt(), white) { addLap() }
        secondaryButton.setOnLongClickListener {
            if (state != State.IDLE) stopAndSave()
            true
        }
        controls.addView(primaryButton, LinearLayout.LayoutParams(0, dp(62), 1f))
        controls.addView(space(12))
        controls.addView(secondaryButton, LinearLayout.LayoutParams(0, dp(62), 1f))
        content.addView(controls)
        content.addView(label("LEFT: START / PAUSE / RESUME     RIGHT: LAP  ·  HOLD: STOP", 9f, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(22))
        })

        // Lap list
        content.addView(sectionTitle("LAP BREAKDOWN", "LATEST FIRST"))
        lapList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 19)
            setPadding(dp(15), dp(5), dp(15), dp(5))
        }
        content.addView(lapList)

        // History and PDF
        val historyHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(25), 0, dp(12)) }
        historyHeader.addView(label("SESSION HISTORY", 16f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val clearHistory = label("CLEAR ALL", 9f, red, true).apply {
            setPadding(dp(9), dp(9), dp(9), dp(9))
            isClickable = true
            setOnClickListener { confirmClearHistory() }
        }
        historyHeader.addView(clearHistory)
        val pdf = actionButton("EXPORT PDF", 0xFF153039.toInt(), cyan) { exportPdf() }.apply { textSize = 10f }
        historyHeader.addView(pdf, LinearLayout.LayoutParams(dp(104), dp(38)))
        content.addView(historyHeader)
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyList)
        val poweredText = TextView(this).apply {
            val full = "Powered by: Shorif Uddin Suzan"
            val span = SpannableString(full)
            span.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/shorifsuzan"))) }
                    catch (_: Exception) { Toast.makeText(this@MainActivity, "Unable to open Facebook", Toast.LENGTH_SHORT).show() }
                }
            }, 12, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = span
            textSize = 10f
            setTextColor(muted)
            gravity = Gravity.CENTER
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = android.graphics.Color.TRANSPARENT
            setPadding(0, dp(27), 0, dp(8))
        }
        content.addView(poweredText)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        renderLaps()
    }

    private fun statColumn(title: String, value: String, color: Int): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(label(title, 9f, muted, true))
        val valueView = label(value, 15f, color, true).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(valueView)
        return col to valueView
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(20), 0, dp(10))
        addView(label(title, 15f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(subtitle, 9f, muted, true))
    }

    private fun primaryAction() {
        when (state) {
            State.IDLE, State.PAUSED -> startOrResume()
            State.RUNNING -> pause()
        }
    }

    private fun startOrResume() {
        if (state == State.IDLE) {
            laps.clear()
            accumulated = 0L
            lastLapMark = 0L
            renderLaps()
        }
        startedAt = SystemClock.elapsedRealtime()
        state = State.RUNNING
        stateText.text = "STOPWATCH RUNNING"
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        updateControls()
    }

    private fun pause() {
        if (state != State.RUNNING) return
        accumulated = elapsed()
        state = State.PAUSED
        handler.removeCallbacks(ticker)
        timerText.text = formatTime(accumulated)
        stateText.text = "PAUSED  ·  READY TO RESUME"
        updateControls()
    }

    private fun addLap() {
        if (state != State.RUNNING && state != State.PAUSED) {
            Toast.makeText(this, "Start the stopwatch first", Toast.LENGTH_SHORT).show()
            return
        }
        val total = elapsed()
        val split = max(0L, total - lastLapMark)
        lastLapMark = total
        laps.add(split)
        renderLaps()
        updateControls()
    }

    private fun stopAndSave() {
        if (state == State.IDLE) return
        val total = elapsed()
        handler.removeCallbacks(ticker)
        state = State.IDLE
        accumulated = total
        timerText.text = formatTime(total)
        stateText.text = "SESSION COMPLETED"
        updateControls()
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(8))
            background = rounded(panel, 18)
        }
        dialogContent.addView(label("SAVE THIS SESSION", 17f, white, true).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        dialogContent.addView(label("Give your stopwatch record a name.", 13f, muted, false).apply {
            setPadding(0, 0, 0, dp(14))
        })
        val input = EditText(this).apply {
            hint = "e.g. Morning practice"
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            backgroundTintList = ColorStateList.valueOf(orange)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        dialogContent.addView(input, LinearLayout.LayoutParams(-1, -2))
        val saveDialog = AlertDialog.Builder(this)
            .setView(dialogContent)
            .setPositiveButton("SAVE", null)
            .setNegativeButton("DISCARD") { _, _ -> latestSession = null }
            .create()
        saveDialog.setOnShowListener {
            saveDialog.window?.setBackgroundDrawable(rounded(panel, 20))
            saveDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(orange)
            saveDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(muted)
            saveDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val name = input.text.toString().trim().ifBlank { "Stopwatch Session" }
                val session = Session(name, SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()), total, laps.toList())
                latestSession = session
                history.add(0, session)
                persistHistory()
                renderHistory()
                Toast.makeText(this, "Session saved to History", Toast.LENGTH_SHORT).show()
                saveDialog.dismiss()
            }
            input.requestFocus()
            saveDialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        saveDialog.show()
    }

    private fun confirmReset() {
        if (state == State.IDLE && accumulated == 0L && laps.isEmpty()) {
            Toast.makeText(this, "Stopwatch is already reset", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Reset stopwatch?")
            .setMessage("Current time and lap splits will be cleared. Saved history will remain safe.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RESET") { _, _ -> resetStopwatch() }
            .show()
    }

    private fun resetStopwatch() {
        handler.removeCallbacks(ticker)
        state = State.IDLE
        startedAt = 0L
        accumulated = 0L
        lastLapMark = 0L
        laps.clear()
        latestSession = null
        timerText.text = formatTime(0L)
        stateText.text = "READY TO BEGIN"
        renderLaps()
        updateControls()
    }

    private fun confirmDeleteSession(session: Session) {
        AlertDialog.Builder(this)
            .setTitle("Delete saved session?")
            .setMessage("\"${session.name}\" will be permanently removed from history.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE") { _, _ ->
                history.remove(session)
                if (latestSession == session) latestSession = null
                persistHistory()
                renderHistory()
            }
            .show()
    }

    private fun confirmClearHistory() {
        if (history.isEmpty()) {
            Toast.makeText(this, "History is already empty", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear all history?")
            .setMessage("All saved sessions will be permanently deleted.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CLEAR ALL") { _, _ ->
                history.clear()
                latestSession = null
                persistHistory()
                renderHistory()
            }
            .show()
    }

    private fun elapsed(): Long = if (state == State.RUNNING) accumulated + (SystemClock.elapsedRealtime() - startedAt) else accumulated

    private fun updateControls() {
        if (!::primaryButton.isInitialized) return
        primaryButton.text = when (state) {
            State.IDLE -> "START"
            State.RUNNING -> "PAUSE"
            State.PAUSED -> "RESUME"
        }
        primaryButton.setTextColor(if (state == State.RUNNING) white else bg)
        primaryButton.background = rounded(if (state == State.RUNNING) 0xFF247E78.toInt() else cyan, 18)
        secondaryButton.text = if (state == State.IDLE) "LAP" else "LAP  /  HOLD TO STOP"
        secondaryButton.isEnabled = state != State.IDLE
        secondaryButton.alpha = if (state == State.IDLE) .5f else 1f
        lapCountText.text = laps.size.toString()
        val avg = if (laps.isNotEmpty()) laps.average().toLong() else 0L
        averageText.text = if (laps.isNotEmpty()) formatShort(avg) else "—"
        bestText.text = if (laps.isNotEmpty()) formatShort(laps.minOrNull() ?: 0L) else "—"
    }

    private fun renderLaps() {
        if (!::lapList.isInitialized) return
        lapList.removeAllViews()
        if (laps.isEmpty()) {
            lapList.addView(label("Your lap splits will appear here after the first lap.", 12f, muted, false).apply { setPadding(dp(4), dp(15), dp(4), dp(15)) })
            return
        }
        laps.indices.reversed().forEach { index ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(12)) }
            row.addView(label("LAP ${(index + 1).toString().padStart(2, '0')}", 12f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(formatTime(laps[index]), 14f, white, true))
            lapList.addView(row)
            if (index != 0) lapList.addView(View(this).apply { setBackgroundColor(0xFF243147.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun renderHistory() {
        if (!::historyList.isInitialized) return
        historyList.removeAllViews()
        if (history.isEmpty()) {
            historyList.addView(label("No saved sessions yet. Stop a timer to create your first record.", 12f, muted, false).apply {
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            return
        }
        history.take(30).forEach { session ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(panel, 17)
                setOnClickListener { latestSession = session; exportPdf() }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val sessionTitle = label(session.name, 14f, white, true).apply {
                isClickable = true
                setOnClickListener { latestSession = session; exportPdf() }
            }
            top.addView(sessionTitle, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(label(formatTime(session.total), 13f, cyan, true).apply {
                isClickable = true
                setOnClickListener { latestSession = session; exportPdf() }
            })
            card.addView(top)
            val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            bottom.addView(label("${session.date}   ·   ${session.laps.size} laps", 10f, muted, false), LinearLayout.LayoutParams(0, -2, 1f))
            bottom.addView(label("DELETE", 9f, red, true).apply {
                setPadding(dp(10), dp(8), dp(4), dp(4))
                isClickable = true
                setOnClickListener { confirmDeleteSession(session) }
            })
            card.addView(bottom)
            historyList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    private fun exportPdf() {
        val session = latestSession ?: if (history.isNotEmpty()) history.first() else null
        if (session == null) {
            Toast.makeText(this, "No saved session to export", Toast.LENGTH_SHORT).show()
            return
        }
        val safe = session.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30).ifBlank { "Stopwatch" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "$safe.pdf")
        }
        pdfPicker.launch(intent)
    }

    private fun writePdf(uri: Uri, session: Session) {
        try {
            val doc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            val c: Canvas = page.canvas
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(15, 29, 48)
                textSize = 25f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            c.drawText("RMG TIMER", 42f, 58f, p)
            p.textSize = 13f
            p.color = android.graphics.Color.rgb(40, 160, 150)
            c.drawText("PRECISION TIME STUDY REPORT", 42f, 82f, p)
            p.color = android.graphics.Color.DKGRAY
            p.textSize = 17f
            c.drawText(session.name, 42f, 132f, p)
            p.textSize = 11f
            c.drawText("Date: ${session.date}", 42f, 157f, p)
            p.textSize = 14f
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText("Total time: ${formatTime(session.total)}", 42f, 196f, p)
            val avg = if (session.laps.isNotEmpty()) session.laps.average().toLong() else 0L
            c.drawText("Lap quantity: ${session.laps.size}", 42f, 222f, p)
            c.drawText("Lap average: ${if (session.laps.isEmpty()) "—" else formatTime(avg)}", 42f, 248f, p)
            p.textSize = 12f
            p.color = android.graphics.Color.rgb(40, 160, 150)
            c.drawText("LAP DETAILS", 42f, 294f, p)
            p.color = android.graphics.Color.DKGRAY
            p.typeface = Typeface.DEFAULT
            var y = 322f
            session.laps.forEachIndexed { i, value ->
                if (y > 790f) return@forEachIndexed
                c.drawText("Lap ${(i + 1).toString().padStart(2, '0')}", 48f, y, p)
                c.drawText(formatTime(value), 450f, y, p)
                y += 25f
            }
            doc.finishPage(page)
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close()
            Toast.makeText(this, "PDF report saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "PDF export failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun persistHistory() {
        val arr = JSONArray()
        history.take(100).forEach { s ->
            arr.put(JSONObject().put("name", s.name).put("date", s.date).put("total", s.total).put("laps", JSONArray(s.laps)))
        }
        getSharedPreferences("chronoflow", MODE_PRIVATE).edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory(): MutableList<Session> {
        val out = mutableListOf<Session>()
        try {
            val arr = JSONArray(getSharedPreferences("chronoflow", MODE_PRIVATE).getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val a = o.getJSONArray("laps")
                val ls = (0 until a.length()).map { a.getLong(it) }
                out.add(Session(o.getString("name"), o.getString("date"), o.getLong("total"), ls))
            }
        } catch (_: Exception) { }
        return out
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if ((event?.repeatCount ?: 0) > 0) return true
            primaryAction()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if ((event?.repeatCount ?: 0) == 0) volumeDownLongPressed = false
            else if (event?.repeatCount == 1) {
                volumeDownLongPressed = true
                if (state != State.IDLE) stopAndSave()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!volumeDownLongPressed) addLap()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1_000) % 60
        val cs = (ms % 1_000) / 10
        return String.format(Locale.US, "%02d:%02d:%02d.%02d", h, m, s, cs)
    }

    private fun formatShort(ms: Long): String {
        val s = ms / 1_000
        val cs = (ms % 1_000) / 10
        return String.format(Locale.US, "%02d.%02d s", s, cs)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun actionButton(text: String, color: Int, textColor: Int, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        background = rounded(color, 18)
        setOnClickListener { action() }
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun space(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
}
