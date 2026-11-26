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
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private enum class State { IDLE, RUNNING, PAUSED }
    private data class Lap(val number: Int, val millis: Long)
    private data class Session(val name: String, val date: String, val total: Long, val laps: List<Long>)

    private val bg = 0xFF080E1B.toInt()
    private val panel = 0xFF111C2E.toInt()
    private val muted = 0xFF91A4BC.toInt()
    private val cyan = 0xFF35D6C5.toInt()
    private val white = 0xFFF4F8FF.toInt()
    private val orange = 0xFFFFB86B.toInt()
    private var state = State.IDLE
    private var startedAt = 0L
    private var accumulated = 0L
    private var lastLapMark = 0L
    private var laps = mutableListOf<Lap>()
    private var history = mutableListOf<Session>()
    private var latestSession: Session? = null
    private var volumeDownLongPressed = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timerText: TextView
    private lateinit var stateText: TextView
    private lateinit var lapCountText: TextView
    private lateinit var averageText: TextView
    private lateinit var lapList: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var lapButton: Button
    private lateinit var stopButton: Button

    private val ticker = object : Runnable {
        override fun run() {
            if (state == State.RUNNING) {
                timerText.text = formatTime(elapsed())
                handler.postDelayed(this, 30)
            }
        }
    }

    private val pdfPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
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
        history = loadHistory()
        buildScreen()
        renderHistory()
        updateControls()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(30)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply { text = "◉  CHRONOFLOW"; textSize = 17f; setTextColor(white); typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = .08f }
        val badge = TextView(this).apply { text = "STOPWATCH"; textSize = 10f; setTextColor(cyan); setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(0xFF153039.toInt()) }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(badge)
        content.addView(header)
        content.addView(label("PRECISION TIMEKEEPING", 10f, muted, true).apply { setPadding(0, dp(24), 0, dp(8)) })

        val clockCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18), dp(28), dp(18), dp(24)); background = rounded(panel, 26) }
        stateText = label("READY WHEN YOU ARE", 10f, cyan, true).apply { letterSpacing = .16f }
        clockCard.addView(stateText)
        val ring = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(270), dp(270)).apply { topMargin = dp(18); bottomMargin = dp(12) } }
        val circle = View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF0D1727.toInt()); setStroke(dp(2), 0xFF21404B.toInt()) } }
        ring.addView(circle, FrameLayout.LayoutParams(-1, -1))
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        timerText = label("00:00:00.00", 34f, white, true).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); includeFontPadding = false }
        inner.addView(timerText)
        inner.addView(label("HOURS : MINUTES : SECONDS", 9f, muted, false).apply { setPadding(0, dp(9), 0, 0); letterSpacing = .08f })
        ring.addView(inner, FrameLayout.LayoutParams(-1, -1))
        clockCard.addView(ring)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lapCountText = label("0 LAPS", 12f, white, true)
        averageText = label("AVG  —", 12f, orange, true)
        stats.addView(lapCountText); stats.addView(space(24)); stats.addView(averageText)
        clockCard.addView(stats)
        content.addView(clockCard)

        val buttons1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(9)) }
        startButton = actionButton("START", cyan, bg) { startOrResume() }
        pauseButton = actionButton("PAUSE", 0xFF24334A.toInt(), white) { pause() }
        buttons1.addView(startButton, LinearLayout.LayoutParams(0, dp(54), 1f)); buttons1.addView(space(10)); buttons1.addView(pauseButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        content.addView(buttons1)
        val buttons2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lapButton = actionButton("LAP", 0xFF183C43.toInt(), cyan) { addLap() }
        stopButton = actionButton("STOP", 0xFF422A31.toInt(), 0xFFFF8A9A.toInt()) { stopAndSave() }
        buttons2.addView(lapButton, LinearLayout.LayoutParams(0, dp(48), 1f)); buttons2.addView(space(10)); buttons2.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(buttons2)
        content.addView(label("VOLUME KEYS  ·  UP: START/PAUSE   DOWN: LAP   HOLD DOWN: STOP", 9f, muted, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(20)) })

        content.addView(sectionTitle("LAP DETAILS", "LIVE SPLITS"))
        lapList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 18); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        content.addView(lapList)
        val historyHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(24), 0, dp(12)) }
        historyHeader.addView(label("HISTORY", 17f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val pdf = actionButton("SAVE PDF", 0xFF153039.toInt(), cyan) { exportPdf() }.apply { textSize = 11f }
        historyHeader.addView(pdf, LinearLayout.LayoutParams(dp(104), dp(38)))
        content.addView(historyHeader)
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(historyList)
        content.addView(label("CHRONOFLOW  •  MADE FOR EVERY SECOND", 9f, 0xFF53657B.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(26), 0, 0) })
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        renderLaps()
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(22), 0, dp(10))
        addView(label(title, 16f, white, true), LinearLayout.LayoutParams(0, -2, 1f)); addView(label(subtitle, 9f, muted, true))
    }

    private fun startOrResume() {
        if (state == State.RUNNING) { pause(); return }
        if (state == State.IDLE) { laps.clear(); accumulated = 0L; lastLapMark = 0L; renderLaps() }
        startedAt = SystemClock.elapsedRealtime(); state = State.RUNNING
        stateText.text = "STOPWATCH RUNNING"; handler.removeCallbacks(ticker); handler.post(ticker); updateControls()
    }

    private fun pause() {
        if (state != State.RUNNING) return
        accumulated = elapsed(); state = State.PAUSED; handler.removeCallbacks(ticker)
        timerText.text = formatTime(accumulated); stateText.text = "PAUSED  ·  READY TO RESUME"; updateControls()
    }

    private fun addLap() {
        if (state != State.RUNNING && state != State.PAUSED) return
        val total = elapsed(); val split = max(0L, total - lastLapMark); lastLapMark = total
        laps.add(Lap(laps.size + 1, split)); renderLaps(); updateControls()
    }

    private fun stopAndSave() {
        if (state == State.IDLE) return
        val total = elapsed(); handler.removeCallbacks(ticker); state = State.IDLE; accumulated = total
        timerText.text = formatTime(total); stateText.text = "SESSION COMPLETED"
        updateControls()
        val input = EditText(this).apply { hint = "e.g. Morning practice"; setSingleLine(true); setTextColor(white); setHintTextColor(muted) }
        AlertDialog.Builder(this).setTitle("Save this session").setMessage("Give your stopwatch record a name.").setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Stopwatch Session" }
                val session = Session(name, SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()), total, laps.map { it.millis })
                latestSession = session; history.add(0, session); persistHistory(); renderHistory()
                Toast.makeText(this, "Session saved to History", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("DISCARD") { _, _ -> latestSession = null }.show()
    }

    private fun elapsed(): Long = if (state == State.RUNNING) accumulated + (SystemClock.elapsedRealtime() - startedAt) else accumulated
    private fun updateControls() {
        startButton.text = if (state == State.PAUSED) "RESUME" else if (state == State.RUNNING) "RUNNING" else "START"
        startButton.isEnabled = state != State.RUNNING
        startButton.alpha = if (startButton.isEnabled) 1f else .45f
        pauseButton.isEnabled = state == State.RUNNING; pauseButton.alpha = if (pauseButton.isEnabled) 1f else .45f
        lapButton.isEnabled = state != State.IDLE; lapButton.alpha = if (lapButton.isEnabled) 1f else .45f
        stopButton.isEnabled = state != State.IDLE; stopButton.alpha = if (stopButton.isEnabled) 1f else .45f
        lapCountText.text = "${laps.size} LAPS"
        val avg = if (laps.isNotEmpty()) laps.map { it.millis }.average().toLong() else 0L
        averageText.text = if (laps.isNotEmpty()) "AVG  ${formatTime(avg)}" else "AVG  —"
    }

    private fun renderLaps() {
        if (!::lapList.isInitialized) return
        lapList.removeAllViews()
        if (laps.isEmpty()) { lapList.addView(label("Your lap times will appear here.", 12f, muted, false).apply { setPadding(dp(4), dp(14), dp(4), dp(14)) }); return }
        laps.asReversed().forEach { lap ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
            row.addView(label("LAP ${lap.number.toString().padStart(2, '0')}", 12f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(formatTime(lap.millis), 14f, white, true)); lapList.addView(row)
            if (lap != laps.first()) lapList.addView(View(this).apply { setBackgroundColor(0xFF243147.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun renderHistory() {
        if (!::historyList.isInitialized) return
        historyList.removeAllViews()
        if (history.isEmpty()) { historyList.addView(label("No saved sessions yet. Stop a timer to create your first record.", 12f, muted, false).apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }); return }
        history.take(30).forEach { session ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(13), dp(16), dp(13)); background = rounded(panel, 16); setOnClickListener { latestSession = session; exportPdf() } }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(session.name, 14f, white, true), LinearLayout.LayoutParams(0, -2, 1f)); top.addView(label(formatTime(session.total), 14f, cyan, true))
            card.addView(top); card.addView(label("${session.date}   •   ${session.laps.size} laps", 10f, muted, false).apply { setPadding(0, dp(6), 0, 0) })
            val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }; historyList.addView(card, lp)
        }
    }

    private fun exportPdf() {
        val session = latestSession ?: if (history.isNotEmpty()) history.first() else null
        if (session == null) { Toast.makeText(this, "No saved session to export", Toast.LENGTH_SHORT).show(); return }
        val safe = session.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30).ifBlank { "Stopwatch" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, "$safe.pdf") }
        pdfPicker.launch(intent)
    }

    private fun writePdf(uri: Uri, session: Session) {
        try {
            val doc = PdfDocument(); val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create(); val page = doc.startPage(pageInfo); val c: Canvas = page.canvas
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(15, 29, 48); textSize = 25f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
            c.drawText("CHRONOFLOW", 42f, 58f, p)
            p.textSize = 13f; p.color = android.graphics.Color.rgb(40, 160, 150); c.drawText("STOPWATCH SESSION REPORT", 42f, 82f, p)
            p.color = android.graphics.Color.DKGRAY; p.textSize = 17f; c.drawText(session.name, 42f, 132f, p)
            p.textSize = 11f; c.drawText("Date: ${session.date}", 42f, 157f, p)
            p.textSize = 14f; p.typeface = Typeface.DEFAULT_BOLD; c.drawText("Total time: ${formatTime(session.total)}", 42f, 196f, p)
            val avg = if (session.laps.isNotEmpty()) session.laps.average().toLong() else 0L
            c.drawText("Lap quantity: ${session.laps.size}", 42f, 222f, p); c.drawText("Lap average: ${if (session.laps.isEmpty()) "—" else formatTime(avg)}", 42f, 248f, p)
            p.textSize = 12f; p.color = android.graphics.Color.rgb(40, 160, 150); c.drawText("LAP DETAILS", 42f, 294f, p)
            p.color = android.graphics.Color.DKGRAY; p.typeface = Typeface.DEFAULT; var y = 322f
            session.laps.forEachIndexed { i, value ->
                if (y > 790f) return@forEachIndexed
                c.drawText("Lap ${(i + 1).toString().padStart(2, '0')}", 48f, y, p); c.drawText(formatTime(value), 450f, y, p); y += 25f
            }
            doc.finishPage(page); contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }; doc.close()
            Toast.makeText(this, "PDF report saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "PDF export failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
    }

    private fun persistHistory() {
        val arr = JSONArray()
        history.take(100).forEach { s -> arr.put(JSONObject().put("name", s.name).put("date", s.date).put("total", s.total).put("laps", JSONArray(s.laps))) }
        getSharedPreferences("chronoflow", MODE_PRIVATE).edit().putString("history", arr.toString()).apply()
    }
    private fun loadHistory(): MutableList<Session> {
        val out = mutableListOf<Session>()
        try { val arr = JSONArray(getSharedPreferences("chronoflow", MODE_PRIVATE).getString("history", "[]")); for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val a = o.getJSONArray("laps"); val ls = (0 until a.length()).map { a.getLong(it) }; out.add(Session(o.getString("name"), o.getString("date"), o.getLong("total"), ls)) } } catch (_: Exception) { }
        return out
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if ((event?.repeatCount ?: 0) > 0) return true
            if (state == State.RUNNING) pause() else startOrResume()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if ((event?.repeatCount ?: 0) == 0) volumeDownLongPressed = false
            else if (event?.repeatCount == 1) { volumeDownLongPressed = true; stopAndSave() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!volumeDownLongPressed) {
                if (state == State.RUNNING || state == State.PAUSED) addLap()
                else Toast.makeText(this, "Start the stopwatch first", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() { handler.removeCallbacks(ticker); super.onDestroy() }
    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000; val m = (ms / 60_000) % 60; val s = (ms / 1_000) % 60; val cs = (ms % 1_000) / 10
        return String.format(Locale.US, "%02d:%02d:%02d.%02d", h, m, s, cs)
    }
    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply { this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private fun actionButton(text: String, color: Int, textColor: Int, action: () -> Unit) = Button(this).apply { this.text = text; setTextColor(textColor); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false; background = rounded(color, 15); setOnClickListener { action() }; stateListAnimator = null; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0) }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun space(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
}
