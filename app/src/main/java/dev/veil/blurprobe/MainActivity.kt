package dev.veil.blurprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 状態・記録・設定をまとめた画面。
 *
 * 触れるつまみは「最大ぼかしに到達するまでの時間」ひとつだけ。
 * 猶予はそこから比例で決まるので、スライダー 1 本でカーブ全体の速さが変わる。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var settings: VeilSettings
    private lateinit var log: UsageLog

    private lateinit var statusText: TextView
    private lateinit var recordText: TextView
    /** アプリごとのスライダーと表示。pkg をキーに引く */
    private val paceLabels = HashMap<String, TextView>()
    private val paceDetails = HashMap<String, TextView>()
    private lateinit var powerButton: Button
    private lateinit var powerHint: TextView

    /** 長押しの開始時刻。0 は押していない */
    private var holdStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VeilSettings(this)
        log = UsageLog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(22), dp(48), dp(22), dp(40))
        }

        root.addView(heading("Veil"))
        root.addView(
            body(
                "ショート動画を見続けるほど画面がぼけていきます。" +
                    "見るのをやめるとゆっくり戻ります。"
            )
        )

        // --- 状態 ---
        root.addView(sectionLabel("状態"), marginTop(dp(28)))
        statusText = mono()
        root.addView(statusText, matchWidth())

        // --- 記録 ---
        root.addView(sectionLabel("記録"), marginTop(dp(26)))
        recordText = mono()
        root.addView(recordText, matchWidth())

        // --- ペース（アプリごと） ---
        root.addView(sectionLabel("ペース"), marginTop(dp(26)))
        root.addView(
            body(
                "最大までぼけきる時間をアプリごとに決めます。" +
                    "無干渉の時間はここから 30% で自動的に決まります。"
            )
        )
        addPaceControl(root, Targets.YOUTUBE)
        addPaceControl(root, Targets.X)

        // --- 有効 / 無効 ---
        root.addView(sectionLabel("機能の入切"), marginTop(dp(26)))
        powerButton = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(powerButton)
        powerHint = body("")
        root.addView(powerHint)
        wirePowerButton()

        // --- 権限 ---
        root.addView(sectionLabel("権限"), marginTop(dp(26)))
        root.addView(action("ユーザー補助を開く") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(action("通知を許可") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })
        root.addView(
            body(
                "クイック設定に「Veil」タイルを置いておくと、通知シェードから" +
                    "1 日 1 回のぼかし解除がすぐ使えます。"
            )
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        cancelHold()
        super.onPause()
    }

    private val refresher = object : Runnable {
        override fun run() {
            renderStatus()
            renderRecord()
            renderPace()
            renderPower()
            ui.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------------ 表示

    private fun renderStatus() {
        val svc = ProbeService.instance
        val running = svc != null && serviceEnabled()
        statusText.text = buildString {
            append("常駐      ${if (running) "動作中" else "停止中 ← ユーザー補助をオンに"}\n")
            append("ぼかし    ${if (settings.enabled) "有効" else "無効"}\n")
            if (svc != null) {
                val c = svc.consumptionSeconds()
                append("消費量    ${formatDuration(c)}相当\n")
                append("解除      ${if (svc.resetAvailable()) "残り1回" else "本日使用済"}")
            } else {
                append("消費量    —")
            }
        }
    }

    private fun renderRecord() {
        val ytWeek = log.weekSeconds(0, Targets.YOUTUBE)
        val xWeek = log.weekSeconds(0, Targets.X)
        val ytChange = log.formatChange(log.weekChangePercent(Targets.YOUTUBE))
        val xChange = log.formatChange(log.weekChangePercent(Targets.X))
        val allChange = log.formatChange(log.weekChangePercent())
        val streak = ConsumptionStore(this).daysSinceReset()

        recordText.text = buildString {
            append("今週      YouTube ${formatDuration(ytWeek)}   X ${formatDuration(xWeek)}\n")
            append("先週比    YouTube $ytChange   X $xChange\n")
            append("合計      ${formatDuration(log.weekSeconds(0))}（先週比 $allChange）\n")
            append("累計      ${formatDuration(log.totalSeconds())}\n")
            append(
                "解除      " + when {
                    streak == null -> "一度も使っていません"
                    streak == 0 -> "今日使いました"
                    else -> "${streak}日連続で未使用"
                }
            )
        }
    }

    /** アプリ 1 つぶんのスライダーと内訳を組む */
    private fun addPaceControl(parent: LinearLayout, pkg: String) {
        val title = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        }
        parent.addView(title)
        paceLabels[pkg] = title

        val bar = SeekBar(this).apply {
            max = VeilSettings.MAX_MINUTES - VeilSettings.MIN_MINUTES
            progress = settings.fullMinutesFor(pkg) - VeilSettings.MIN_MINUTES
            setPadding(0, dp(8), 0, dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        settings.setFullMinutes(pkg, p + VeilSettings.MIN_MINUTES)
                        renderPace()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    ProbeService.instance?.reloadSettings()
                }
            })
        }
        parent.addView(bar, matchWidth())

        val detail = mono()
        parent.addView(detail, matchWidth())
        paceDetails[pkg] = detail
    }

    private fun renderPace() {
        listOf(Targets.YOUTUBE to "YouTube", Targets.X to "X").forEach { (pkg, name) ->
            paceLabels[pkg]?.text = "$name　${settings.fullMinutesFor(pkg)}分で最大"
            paceDetails[pkg]?.text = buildString {
                append("無干渉    ${formatDuration(settings.graceSecondsFor(pkg))}まで\n")
                append("体感      ${formatDuration(settings.noticeableSecondsFor(pkg))}あたりから\n")
                append("最大      ${formatDuration(settings.fullSecondsIntFor(pkg))}　半径 ${Targets.rMaxOf(pkg)}")
            }
        }
    }

    private fun renderPower() {
        if (holdStart != 0L) return   // 長押し中は上書きしない
        if (settings.enabled) {
            powerButton.text = "長押しで停止（3分）"
            powerButton.background = pill(0xFF2B1E1E.toInt())
            powerButton.setTextColor(0xFFF2B8B5.toInt())
            powerHint.text =
                "止めるには3分間押し続ける必要があります。指を離すとやり直しです。" +
                    "衝動的にやめられないための仕掛けです。"
        } else {
            powerButton.text = "再開する"
            powerButton.background = pill(ACCENT)
            powerButton.setTextColor(0xFF07171A.toInt())
            powerHint.text = "停止中も視聴時間の記録は続いています。再開はタップ一回です。"
        }
    }

    // ------------------------------------------------------------ 長押しで停止

    private fun wirePowerButton() {
        powerButton.setOnTouchListener { _, e ->
            if (!settings.enabled) {
                if (e.action == MotionEvent.ACTION_UP) {
                    settings.enabled = true
                    ProbeService.instance?.reloadSettings()
                    toast("再開しました")
                    renderPower()
                }
                return@setOnTouchListener true
            }
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    holdStart = SystemClock.uptimeMillis()
                    ui.post(holdTick)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
            }
            true
        }
    }

    private val holdTick = object : Runnable {
        override fun run() {
            if (holdStart == 0L) return
            val held = SystemClock.uptimeMillis() - holdStart
            val left = VeilSettings.DISABLE_HOLD_MS - held
            if (left <= 0) {
                holdStart = 0L
                settings.enabled = false
                ProbeService.instance?.reloadSettings()
                toast("ぼかしを停止しました")
                renderPower()
                return
            }
            val pct = (held * 100 / VeilSettings.DISABLE_HOLD_MS).toInt()
            powerButton.text = "あと ${formatDuration((left / 1000).toInt())}　$pct%"
            ui.postDelayed(this, 100)
        }
    }

    private fun cancelHold() {
        if (holdStart == 0L) return
        val held = SystemClock.uptimeMillis() - holdStart
        holdStart = 0L
        if (held > 3000) toast("離してしまいました。最初からです")
        renderPower()
    }

    // ------------------------------------------------------------- view help

    private fun serviceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.id.orEmpty().contains(packageName) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderStatus()
    }

    private fun notifGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 30f
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.14f
        setTextColor(ACCENT)
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xFF97A0A7.toInt())
        setLineSpacing(0f, 1.5f)
        setPadding(0, dp(9), 0, 0)
    }

    private fun mono() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12.5f
        setTextColor(0xFFD6DBDF.toInt())
        setLineSpacing(0f, 1.45f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(CARD)
        }
    }

    private fun action(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = pill(CARD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    private fun marginTop(px: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val BG = 0xFF0E1114.toInt()
        private const val CARD = 0xFF1B2025.toInt()
        private const val ACCENT = 0xFF35B6C0.toInt()
    }
}
