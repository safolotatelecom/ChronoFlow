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
    private data class OperatorInfo(
        val name: String,
        val id: String,
        val style: String,
        val process: String,
        val allowance: Double
    )
    private data class Session(
        val name: String,
        val date: String,
        val total: Long,
        val laps: List<Long>,
        val operator: OperatorInfo
    )

    private val bg = 0xFF061329.toInt()
    private val panel = 0xFF0D2142.toInt()
    private val panelAlt = 0xFF142D52.toInt()
    private val muted = 0xFF9AAFCB.toInt()
    private val cyan = 0xFF16BDF2.toInt()
    private val white = 0xFFF4F7FC.toInt()
    private val orange = 0xFFFFD21F.toInt()
    private val red = 0xFFFF7185.toInt()

    private var state = State.IDLE
    private var startedAt = 0L
    private var accumulated = 0L
    private var lastLapMark = 0L
    private val laps = mutableListOf<Long>()
    private var history = mutableListOf<Session>()
    private var latestSession: Session? = null
    private var operatorInfo: OperatorInfo? = null
    private var volumeDownLongPressed = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var timerText: TextView
    private lateinit var stateText: TextView
    private lateinit var dateText: TextView
    private lateinit var lapCountText: TextView
    private lateinit var averageText: TextView
    private lateinit var bestText: TextView
    private lateinit var capacityText: TextView
    private lateinit var operatorSummary: TextView
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
        showOperatorEntryDialog()
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
        val brandIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "RMG Timer stopwatch runner logo"
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        header.addView(brandIcon, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        brandColumn.addView(label("RMG TIMER", 18f, white, true).apply { letterSpacing = .06f })
        brandColumn.addView(label("PRECISION TIME STUDY", 9f, orange, true).apply { setPadding(0, dp(3), 0, 0); letterSpacing = .12f })
        header.addView(brandColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val today = SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
        dateText = label(today, 10f, cyan, true).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(0xFF153039.toInt(), 30) }
        header.addView(dateText)
        content.addView(header)
        operatorSummary = label("OPERATOR DETAILS  ·  ENTER TO BEGIN", 10f, muted, true).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panelAlt, 14)
            setOnClickListener { showOperatorEntryDialog() }
        }
        content.addView(operatorSummary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

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
        // Signature circular precision dial
        val dial = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF091A35.toInt())
                setStroke(dp(3), cyan)
            }
            elevation = dp(5).toFloat()
        }
        val dialContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        timerText = label("00:00:00.00", 28f, white, true).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            letterSpacing = -.035f
            setSingleLine(true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28f)
        }
        dialContent.addView(timerText, LinearLayout.LayoutParams(-1, -2))
        dialContent.addView(label("HOURS  ·  MINUTES  ·  SECONDS", 8f, muted, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .035f
            setPadding(0, dp(9), 0, 0)
        })
        dial.addView(dialContent, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        hero.addView(dial, LinearLayout.LayoutParams(dp(258), dp(258)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
            bottomMargin = dp(22)
        })
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
        val capacityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFF092D4A.toInt(), 15)
        }
        capacityCard.addView(label("OPERATOR CAPACITY / HOUR", 9f, muted, true))
        capacityText = label("— PCS / HR", 25f, cyan, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
            includeFontPadding = false
        }
        capacityCard.addView(capacityText)
        hero.addView(capacityCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(17) })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

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

    private fun showOperatorEntryDialog() {
        val prefs = getSharedPreferences("chronoflow", MODE_PRIVATE)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(6))
            background = rounded(panel, 20)
        }
        form.addView(label("NEW TIME STUDY", 18f, white, true).apply { setPadding(0, 0, 0, dp(5)) })
        form.addView(label("Enter operator and operation details to begin.", 11f, muted, false).apply { setPadding(0, 0, 0, dp(12)) })
        fun field(title: String, key: String, hintText: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            form.addView(label(title.uppercase(Locale.getDefault()), 9f, cyan, true).apply { setPadding(0, dp(7), 0, dp(3)) })
            return EditText(this).apply {
                hint = hintText
                setText(prefs.getString(key, "") ?: "")
                setSingleLine(true)
                textSize = 15f
                setTextColor(white)
                setHintTextColor(muted)
                this.inputType = inputType
                backgroundTintList = ColorStateList.valueOf(orange)
                setPadding(dp(5), dp(7), dp(5), dp(7))
                form.addView(this, LinearLayout.LayoutParams(-1, dp(44)))
            }
        }
        val nameInput = field("Name", "operator_name", "Operator name", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val idInput = field("ID", "operator_id", "Employee ID")
        val styleInput = field("Style", "operator_style", "Style number")
        val processInput = field("Process", "operator_process", "Operation / process")
        val allowanceInput = field("Allowance %", "operator_allowance", "Example: 15", android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
        if (prefs.getString("operator_allowance", null) == null) allowanceInput.setText("0")

        val formScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(form, ViewGroup.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setView(formScroll)
            .setPositiveButton("SAVE & CONTINUE", null)
            .setNegativeButton("CANCEL", null)
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(panel, 20))
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(orange)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(muted)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                if (operatorInfo == null) {
                    Toast.makeText(this, "Please enter the required details to continue", Toast.LENGTH_SHORT).show()
                } else dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val name = nameInput.text.toString().trim()
                val id = idInput.text.toString().trim()
                val style = styleInput.text.toString().trim()
                val process = processInput.text.toString().trim()
                val allowance = allowanceInput.text.toString().trim().toDoubleOrNull()
                if (name.isBlank() || id.isBlank() || style.isBlank() || process.isBlank() || allowance == null || allowance < 0.0) {
                    Toast.makeText(this, "Fill all fields and enter a valid allowance percentage", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                operatorInfo = OperatorInfo(name, id, style, process, allowance)
                prefs.edit()
                    .putString("operator_name", name)
                    .putString("operator_id", id)
                    .putString("operator_style", style)
                    .putString("operator_process", process)
                    .putString("operator_allowance", allowance.toString())
                    .apply()
                operatorSummary.text = "${name.uppercase(Locale.getDefault())}  ·  ID: $id\nSTYLE: $style  ·  PROCESS: $process  ·  ALLOWANCE: ${formatPercent(allowance)}"
                updateControls()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun formatPercent(value: Double): String = if (value % 1.0 == 0.0) "${value.toInt()}%" else String.format(Locale.US, "%.2f%%", value)

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
                val operator = operatorInfo
                if (operator == null) {
                    Toast.makeText(this, "Enter operator details first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val session = Session(name, SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()), total, laps.toList(), operator)
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
        primaryButton.background = rounded(if (state == State.RUNNING) 0xFF087EAA.toInt() else orange, 18)
        secondaryButton.text = if (state == State.IDLE) "LAP" else "LAP  /  HOLD TO STOP"
        secondaryButton.isEnabled = state != State.IDLE
        secondaryButton.alpha = if (state == State.IDLE) .5f else 1f
        lapCountText.text = laps.size.toString()
        val avg = if (laps.isNotEmpty()) laps.average() else 0.0
        averageText.text = if (laps.isNotEmpty()) formatShort(avg.toLong()) else "—"
        bestText.text = if (laps.isNotEmpty()) formatShort(laps.minOrNull() ?: 0L) else "—"
        val allowance = operatorInfo?.allowance ?: 0.0
        val allowedMs = avg * (1.0 + allowance / 100.0)
        val capacity = if (allowedMs > 0.0) 3_600_000.0 / allowedMs else 0.0
        capacityText.text = if (capacity > 0.0) String.format(Locale.US, "%.2f PCS / HR", capacity) else "— PCS / HR"
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
            val pageWidth = 595
            val pageHeight = 842
            val avgMs = if (session.laps.isNotEmpty()) session.laps.average() else 0.0
            val allowedMs = avgMs * (1.0 + session.operator.allowance / 100.0)
            val capacity = if (allowedMs > 0.0) 3_600_000.0 / allowedMs else 0.0
            val pageCount = if (session.laps.size <= 5) 1 else 1 + (session.laps.size - 5 + 23) / 24
            for (pageIndex in 0 until pageCount) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                val page = doc.startPage(pageInfo)
                val c = page.canvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
                    paint.color = color
                    paint.textSize = size
                    paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                    c.drawText(value, x, y, paint)
                }
                fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int, radius: Float = 12f) {
                    paint.color = color
                    paint.style = Paint.Style.FILL
                    c.drawRoundRect(left, top, right, bottom, radius, radius, paint)
                }
                val navy = android.graphics.Color.rgb(6, 19, 41)
                val blue = android.graphics.Color.rgb(13, 33, 66)
                val teal = android.graphics.Color.rgb(22, 189, 242)
                val yellow = android.graphics.Color.rgb(255, 210, 31)
                val dark = android.graphics.Color.rgb(24, 39, 59)
                val grey = android.graphics.Color.rgb(104, 119, 139)
                c.drawColor(android.graphics.Color.WHITE)
                rect(0f, 0f, pageWidth.toFloat(), 112f, navy, 0f)
                text("RMG TIMER", 38f, 48f, 27f, android.graphics.Color.WHITE, true)
                text("PRECISION TIME STUDY REPORT", 40f, 72f, 11f, teal, true)
                text("REPORT  ${pageIndex + 1} / $pageCount", 440f, 48f, 9f, android.graphics.Color.WHITE, true)

                if (pageIndex == 0) {
                    text(session.name.take(48), 38f, 145f, 18f, navy, true)
                    text("Date: ${session.date}", 38f, 166f, 10f, grey)
                    rect(32f, 188f, 563f, 330f, blue)
                    text("OPERATOR INFORMATION", 50f, 215f, 11f, teal, true)
                    text("Name", 50f, 244f, 9f, android.graphics.Color.LTGRAY, true)
                    text(session.operator.name.take(34), 50f, 263f, 14f, android.graphics.Color.WHITE, true)
                    text("ID", 315f, 244f, 9f, android.graphics.Color.LTGRAY, true)
                    text(session.operator.id.take(28), 315f, 263f, 14f, android.graphics.Color.WHITE, true)
                    text("Style", 50f, 291f, 9f, android.graphics.Color.LTGRAY, true)
                    text(session.operator.style.take(32), 50f, 310f, 13f, android.graphics.Color.WHITE, true)
                    text("Process", 315f, 291f, 9f, android.graphics.Color.LTGRAY, true)
                    text(session.operator.process.take(30), 315f, 310f, 13f, android.graphics.Color.WHITE, true)
                    text("Allowance", 50f, 337f, 9f, android.graphics.Color.LTGRAY, true)
                    text(formatPercent(session.operator.allowance), 130f, 337f, 12f, yellow, true)

                    rect(32f, 350f, 563f, 477f, 0xFFEAF4FA.toInt())
                    text("TIME STUDY SUMMARY", 50f, 375f, 10f, navy, true)
                    text("Average lap", 50f, 400f, 9f, grey, true)
                    text(if (avgMs > 0.0) formatTime(avgMs.toLong()) else "N/A", 50f, 424f, 15f, navy, true)
                    text("Allowed time", 230f, 400f, 9f, grey, true)
                    text(if (allowedMs > 0.0) formatTime(allowedMs.toLong()) else "N/A", 230f, 424f, 15f, navy, true)
                    text("Total time", 410f, 400f, 9f, grey, true)
                    text(formatTime(session.total), 410f, 424f, 15f, navy, true)
                    rect(32f, 490f, 563f, 575f, navy)
                    text("OPERATOR CAPACITY / HOUR", 50f, 516f, 10f, teal, true)
                    text(if (capacity > 0.0) String.format(Locale.US, "%.2f PCS / HOUR", capacity) else "NOT AVAILABLE - RECORD LAP SPLITS", 50f, 552f, if (capacity > 0.0) 25f else 12f, yellow, true)
                    text("CAPACITY = 3600 ÷ ALLOWED TIME (SECONDS)", 50f, 597f, 9f, grey, true)
                    text("LAP BREAKDOWN", 38f, 638f, 12f, navy, true)
                } else {
                    text("LAP BREAKDOWN (CONTINUED)", 38f, 145f, 14f, navy, true)
                }
                val startRow = if (pageIndex == 0) 0 else 5 + (pageIndex - 1) * 24
                val endRow = if (pageIndex == 0) minOf(session.laps.size, 5) else minOf(session.laps.size, startRow + 24)
                var y = if (pageIndex == 0) 665f else 185f
                if (pageIndex == 0) {
                    text("LAP", 48f, y, 9f, grey, true)
                    text("SPLIT TIME", 460f, y, 9f, grey, true)
                    y += 20f
                } else {
                    text("LAP", 48f, y, 9f, grey, true)
                    text("SPLIT TIME", 460f, y, 9f, grey, true)
                    y += 20f
                }
                if (session.laps.isEmpty() && pageIndex == 0) {
                    text("No lap splits recorded in this session.", 48f, y + 10f, 10f, grey)
                }
                for (i in startRow until endRow) {
                    if (i % 2 == 0) rect(38f, y - 14f, 557f, y + 8f, 0xFFF1F5F9.toInt(), 4f)
                    text("LAP ${(i + 1).toString().padStart(2, '0')}", 48f, y, 10f, dark, true)
                    text(formatTime(session.laps[i]), 460f, y, 10f, dark, true)
                    y += 25f
                }
                text("Powered by: Shorif Uddin Suzan  ·  facebook.com/shorifsuzan", 38f, 817f, 8f, grey)
                doc.finishPage(page)
            }
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
            val op = JSONObject()
                .put("name", s.operator.name)
                .put("id", s.operator.id)
                .put("style", s.operator.style)
                .put("process", s.operator.process)
                .put("allowance", s.operator.allowance)
            arr.put(JSONObject().put("name", s.name).put("date", s.date).put("total", s.total).put("laps", JSONArray(s.laps)).put("operator", op))
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
                val op = o.optJSONObject("operator") ?: JSONObject()
                val operator = OperatorInfo(
                    op.optString("name", "Unknown"),
                    op.optString("id", "—"),
                    op.optString("style", "—"),
                    op.optString("process", "—"),
                    op.optDouble("allowance", 0.0)
                )
                out.add(Session(o.getString("name"), o.getString("date"), o.getLong("total"), ls, operator))
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
