@file:Suppress("DEPRECATION")

package dev.veil.blurprobe

import android.accessibilityservice.AccessibilityService
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min

/**
 * V-01 検証用 v9。
 *
 * Phase 0 で確定したこと:
 *   - TYPE_ACCESSIBILITY_OVERLAY + Window#setBackgroundBlurRadius で
 *     動画レイヤーの上でも実画面がぼける（V-01 通過）
 *   - FLAG_BLUR_BEHIND は実画面に描画されず、しかも入力を奪うので使わない
 *   - 半径 40 で大きな文字も判読不能。実用域は 0〜40
 *
 * v9 の変更:
 *   - 画面上の HUD を撤去（ベール自身がぼけて読めないため）
 *   - 通知シェードを開いている間はぼかしを自動で外す
 *     シェードを開いた時点で対象コンテンツから離れているので、製品としても正しい挙動
 *   - 段階を実用域に合わせて刻み直した
 */
class ProbeService : AccessibilityService() {

    companion object {
        const val TAG = "BlurProbe"
        const val ACTION_CTL = "dev.veil.blurprobe.CTL"
        /**
         * AOSP の目安は 150。それを超える値も検証できるようにしてあるが、
         * 150 超は描画コストが跳ね上がるので常用しないこと。
         */
        const val MAX_RADIUS = 250

        /** アクセシビリティオーバーレイ。通知シェードより上のレイヤーに出る。 */
        const val MODE_ACC = "acc"

        /** 通常のオーバーレイ。システム UI より下のレイヤーなのでシェードを覆わない。 */
        const val MODE_APP = "app"

        private const val CHANNEL_ID = "veil"
        private const val NOTIF_ID = 42
        private const val SYSTEM_UI = "com.android.systemui"

        /** 実測に合わせた段階。40 で大きな文字も読めなくなるため低域を細かく。 */
        val STAGES = intArrayOf(0, 6, 12, 20, 30, 40, 60, 90, 150)

        const val DEFAULT_TTL_SEC = 600

        @Volatile
        var instance: ProbeService? = null
            private set

        @Volatile
        var tile: VeilTile? = null

        fun sigmaOf(radius: Int): Float = 0.57735f * radius + 0.5f
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var dialog: Dialog? = null

    private var mode = MODE_ACC
    private var radius = 0
    private var lastOn = 20
    private var ttlSec = DEFAULT_TTL_SEC

    /** 通知シェードやクイック設定が前面にある間はぼかしを外す。 */
    private var suspendOnShade = true
    private var shadeFront = false

    /**
     * 通知シェードが前面のとき、目標半径の何 % を残すか。
     *
     * 0 にすると完全に素通しになるが、シェードを閉じてから
     * 「前面でなくなった」イベントが届くまでの間、ぼかしが抜けた状態が見える。
     * 少し残しておくと落差が小さくなり、戻りが目立たなくなる。
     */
    private var shadePct = 30

    /**
     * ウィンドウ alpha。-1 は自動（app=0.8 / acc=1.0）。
     *
     * app モードで 0.8 に落としているのは Android 12 のタッチ遮断を避けるため。
     * ただし alpha は背景ブラーの効きも一緒に弱める。合成結果が
     *   alpha × ぼけた画像 + (1-alpha) × 元画像
     * になるので、0.8 だと元画像が 20% 残って文字が読めてしまう。
     * この値を手で動かして、ぼけの強さとタッチ透過の境目を実測するための変数。
     */
    private var alphaPct = -1

    /** 実際にウィンドウへ渡している半径。目標値へ向けて滑らかに動かす。 */
    private var appliedRadius = 0

    /** 目標値まで到達させる時間。0 で即時切り替え。 */
    private var rampMs = 250

    val currentRadius: Int get() = radius

    /** 1 フレームぶん目標へ近づける。到達したら止まる。 */
    private val rampStep = object : Runnable {
        override fun run() {
            val target = effectiveRadius()
            if (appliedRadius == target) return
            val stepCount = max(1, rampMs / 16)
            val delta = max(1, Math.abs(target - appliedRadius) / stepCount.coerceAtLeast(1))
            appliedRadius = if (target > appliedRadius) {
                min(target, appliedRadius + delta)
            } else {
                max(target, appliedRadius - delta)
            }
            pushRadius(appliedRadius)
            if (appliedRadius != target) ui.postDelayed(this, 16)
        }
    }

    private val resetToSafe = Runnable {
        Log.i(TAG, "watchdog: 無操作が続いたので解除")
        radius = 0
        apply()
    }

    private val blurListener = Consumer<Boolean> { enabled ->
        ui.post { Log.i(TAG, "isCrossWindowBlurEnabled -> $enabled"); render(enabled) }
    }

    private val ctl = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { i?.let { handleCommand(it) } }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        createChannel()
        buildVeil()
        wm.addCrossWindowBlurEnabledListener(blurListener)
        registerReceiver(ctl, IntentFilter(ACTION_CTL), Context.RECEIVER_EXPORTED)
        logEnvironment()
        apply()
    }

    /**
     * 通知シェード／クイック設定の開閉を検出する。
     * SystemUI が前面に来た瞬間にぼかしを外し、離れたら戻す。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val front = pkg == SYSTEM_UI
        if (front != shadeFront) {
            shadeFront = front
            Log.i(
                TAG,
                "%d ms  systemUI front=%s (pkg=%s)".format(
                    android.os.SystemClock.uptimeMillis() % 100000, front, pkg
                )
            )
            apply()
        }
    }

    override fun onInterrupt() { /* 未使用 */ }

    override fun onDestroy() {
        ui.removeCallbacks(resetToSafe)
        ui.removeCallbacks(rampStep)
        runCatching { wm.removeCrossWindowBlurEnabledListener(blurListener) }
        runCatching { unregisterReceiver(ctl) }
        tearDownVeil()
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        runCatching { writeMono(false) }
        instance = null
        tile?.sync()
        Log.i(TAG, "service destroyed, screen restored")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 操作

    fun handleCommand(i: Intent) = ui.post {
        when (i.getStringExtra("cmd")) {
            "toggle" -> { toggleInternal(); return@post }
            "up" -> { radius = stage(1); apply(); return@post }
            "down" -> { radius = stage(-1); apply(); return@post }
            "stop" -> { disableSelf(); return@post }
        }
        i.getStringExtra("mode")?.let { setMode(it) }
        if (i.hasExtra("ttl")) ttlSec = max(0, i.getIntExtra("ttl", DEFAULT_TTL_SEC))
        if (i.hasExtra("shade")) suspendOnShade = i.getBooleanExtra("shade", true)
        if (i.hasExtra("ramp")) rampMs = max(0, i.getIntExtra("ramp", 250))
        if (i.hasExtra("shadepct")) shadePct = i.getIntExtra("shadepct", 30).coerceIn(0, 100)
        if (i.hasExtra("a")) alphaPct = i.getIntExtra("a", -1).let { if (it in 0..100) it else -1 }
        if (i.hasExtra("r")) {
            radius = min(MAX_RADIUS, max(0, i.getIntExtra("r", 0)))
            if (radius > 0) lastOn = radius
        }
        if (i.hasExtra("mono")) setMono(i.getBooleanExtra("mono", false))
        if (i.getBooleanExtra("stop", false)) { disableSelf(); return@post }
        apply()
    }

    fun toggleVeil() = ui.post { toggleInternal() }

    private fun toggleInternal() {
        if (radius > 0) { lastOn = radius; radius = 0 } else { radius = max(lastOn, 6) }
        apply()
    }

    private fun stage(dir: Int): Int {
        val idx = STAGES.indexOfFirst { it >= radius }.let { if (it < 0) STAGES.size - 1 else it }
        return STAGES[(idx + dir).coerceIn(0, STAGES.size - 1)].also { if (it > 0) lastOn = it }
    }

    // ------------------------------------------------------------------ veil

    private fun windowType(): Int =
        if (mode == MODE_APP) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun buildVeil() {
        val d = Dialog(this, R.style.VeilDialog)
        d.setCancelable(false)
        d.setContentView(FrameLayout(this))
        val w = d.window ?: run { Log.e(TAG, "dialog.window is null"); return }
        w.setType(windowType())
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0f)
        w.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // app モードでは Android 12 のタッチ遮断を避けるため 0.8 以下に抑える。
            alpha = effectiveAlpha()
        }
        w.setBackgroundDrawable(ColorDrawable(0x01000000))
        runCatching { d.show() }
            .onSuccess { dialog = d; Log.i(TAG, "veil shown mode=$mode") }
            .onFailure { e -> Log.e(TAG, "veil failed mode=$mode: $e") }
    }

    /** -1 のときはモードごとの既定値を使う。 */
    private fun effectiveAlpha(): Float =
        if (alphaPct in 0..100) alphaPct / 100f
        else if (mode == MODE_APP) 0.8f else 1.0f

    private fun tearDownVeil() {
        dialog?.let { d -> runCatching { d.dismiss() } }
        dialog = null
    }

    private fun setMode(m: String) {
        val next = if (m == MODE_APP) MODE_APP else MODE_ACC
        if (next == mode) return
        mode = next
        tearDownVeil()
        buildVeil()
        Log.i(TAG, "mode -> $mode")
    }

    /** 実際に適用する半径。シェードが前面なら 0 に落とす。 */
    private fun effectiveRadius(): Int =
        if (suspendOnShade && shadeFront) radius * shadePct / 100 else radius

    /** ウィンドウへ実際に値を渡す。 */
    private fun pushRadius(r: Int) {
        dialog?.window?.let { w ->
            w.attributes = w.attributes.apply { alpha = effectiveAlpha() }
            w.setBackgroundBlurRadius(r)
        }
    }

    private fun apply() {
        val eff = effectiveRadius()
        ui.removeCallbacks(rampStep)
        if (rampMs <= 0) {
            appliedRadius = eff
            pushRadius(eff)
        } else {
            ui.post(rampStep)
        }
        val enabled = wm.isCrossWindowBlurEnabled
        Log.i(
            TAG,
            "%d ms  mode=%s r=%d eff=%d σ=%.1f a=%.2f shade=%s blur=%s".format(
                android.os.SystemClock.uptimeMillis() % 100000,
                mode, radius, eff, sigmaOf(eff), effectiveAlpha(), shadeFront, enabled
            ) + " shadePct=$shadePct ramp=${rampMs}ms"
        )
        render(enabled)
        ui.removeCallbacks(resetToSafe)
        if (ttlSec > 0 && radius > 0) ui.postDelayed(resetToSafe, ttlSec * 1000L)
    }

    private fun render(blurEnabled: Boolean) {
        postNotification(blurEnabled)
        tile?.sync()
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Veil コントロール", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun postNotification(blurEnabled: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(if (radius > 0) "ぼかし中  r $radius" else "ぼかし解除中")
            .setContentText(
                "$mode ・ σ ${"%.1f".format(sigmaOf(radius))} ・ a ${"%.2f".format(effectiveAlpha())}" +
                    " ・ blur ${if (blurEnabled) "有効" else "無効"}"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(cmdAction(if (radius > 0) "解除" else "復帰", "toggle"))
            .addAction(cmdAction("弱く", "down"))
            .addAction(cmdAction("強く", "up"))
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
            .onFailure { e -> Log.w(TAG, "通知を出せません（権限未許可?）: $e") }
    }

    private fun cmdAction(label: String, cmd: String): Notification.Action {
        val i = Intent(this, CtlReceiver::class.java).putExtra("cmd", cmd)
        val pi = PendingIntent.getBroadcast(
            this, cmd.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null as Icon?, label, pi).build()
    }

    // ------------------------------------------------------- mono (F-2 の下見)

    private fun setMono(on: Boolean) {
        try { writeMono(on) } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS 未付与: ${e.message}")
        }
    }

    private fun writeMono(on: Boolean) {
        Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", 0)
        Settings.Secure.putInt(
            contentResolver, "accessibility_display_daltonizer_enabled", if (on) 1 else 0
        )
    }

    private fun logEnvironment() {
        val b = wm.currentWindowMetrics.bounds
        Log.i(
            TAG,
            "device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "display=${b.width()}x${b.height()} density=${resources.displayMetrics.density}x " +
                "blurEnabled=${wm.isCrossWindowBlurEnabled}"
        )
    }
}
