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
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Veil 本体（M1）。
 *
 * Phase 0 で確定した描画方式の上に、消費量の蓄積・減衰・カーブ適用を載せた版。
 * 判定はまだ仮実装で、対象アプリが前面かどうかだけを見る。
 * Shorts と通常の YouTube 動画は区別していない（M3 で対応）。
 *
 * 描画に関する制約は CLAUDE.md と docs/phase0-findings.md を参照。
 * 要点だけ再掲する。
 *   - FLAG_BLUR_BEHIND は使わない（実画面に描画されず、入力も奪う）
 *   - TYPE_APPLICATION_OVERLAY は使わない（alpha の制約でぼかしが頭打ちになる）
 *   - ウィンドウ alpha は 1.0 から下げない（ブラーが減衰する）
 */
class ProbeService : AccessibilityService() {

    companion object {
        const val TAG = "BlurProbe"
        const val ACTION_CTL = "dev.veil.blurprobe.CTL"
        const val MAX_RADIUS = 250

        private const val CHANNEL_ID = "veil"
        private const val NOTIF_ID = 42
        private const val SYSTEM_UI = "com.android.systemui"

        /** 消費量を数える間隔 */
        private const val TICK_MS = 1000L

        /** 半径を目標値へ寄せる時間 */
        private const val DEFAULT_RAMP_MS = 250

        @Volatile
        var instance: ProbeService? = null
            private set

        @Volatile
        var tile: VeilTile? = null

        fun sigmaOf(radius: Int): Float = 0.57735f * radius + 0.5f
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var store: ConsumptionStore
    private lateinit var settings: VeilSettings
    private lateinit var log: UsageLog

    private var dialog: Dialog? = null

    /** 前面のアプリ。通知シェードが出ても書き換えない（閉じたら元に戻すため） */
    private var frontPkg: String? = null
    private var shadeFront = false
    private var screenOn = true

    /** false にすると消費量を無視して手動の半径を使う。検証用 */
    private var auto = true
    private var manualRadius = 0

    private var rampMs = DEFAULT_RAMP_MS
    private var appliedRadius = 0

    val currentRadius: Int get() = targetRadius()

    // ------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        store = ConsumptionStore(this)
        settings = VeilSettings(this)
        log = UsageLog(this)
        log.pruneIfNeeded()
        createChannel()
        buildVeil()
        wm.addCrossWindowBlurEnabledListener(blurListener)
        registerReceiver(ctl, IntentFilter(ACTION_CTL), Context.RECEIVER_EXPORTED)
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        logEnvironment()
        ui.post(ticker)
        apply()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(rampStep)
        store.stopAccumulating()
        store.persistNow()
        runCatching { wm.removeCrossWindowBlurEnabledListener(blurListener) }
        runCatching { unregisterReceiver(ctl) }
        runCatching { unregisterReceiver(screen) }
        dialog?.let { d -> runCatching { d.dismiss() } }
        dialog = null
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        instance = null
        tile?.sync()
        Log.i(TAG, "service destroyed, screen restored")
        super.onDestroy()
    }

    override fun onInterrupt() { /* 未使用 */ }

    /**
     * 前面アプリの追跡と、通知シェードの開閉検出。
     *
     * シェードが前面のとき frontPkg を書き換えないのは、閉じたときに
     * 元のアプリへ戻す必要があるため。閉じると対象アプリのイベントが
     * 改めて飛んでくるので、そこで shadeFront が false に戻る。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return

        if (pkg == SYSTEM_UI) {
            if (!shadeFront) {
                shadeFront = true
                syncAccumulation()
                apply()
            }
            return
        }
        if (shadeFront || pkg != frontPkg) {
            shadeFront = false
            frontPkg = pkg
            syncAccumulation()
            apply()
        }
    }

    // ------------------------------------------------------------------ 計測

    /**
     * 対象アプリを見ている最中だけ消費量を積む。
     *
     * 機能が無効でも記録は続ける。**止めている間に何分見たのかが分からないと、
     * 止めた効果そのものが測れなくなる**ため。
     */
    private fun shouldAccumulate(): Boolean =
        screenOn && !shadeFront && Targets.isTarget(frontPkg)

    private fun syncAccumulation() {
        if (shouldAccumulate()) store.startAccumulating() else store.stopAccumulating()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (store.accumulating) {
                store.tick()
                frontPkg?.let { log.add(it) }
                apply()
            }
            ui.postDelayed(this, TICK_MS)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            Log.i(TAG, "screenOn=$screenOn")
            syncAccumulation()
            apply()
        }
    }

    // ------------------------------------------------------------------ 適用

    /** いま当てるべき半径。無効化中・対象外・シェード表示中・画面 OFF では 0 */
    private fun targetRadius(): Int {
        if (!settings.enabled) return 0
        if (!auto) return manualRadius
        if (shadeFront || !screenOn) return 0
        return settings.radiusFor(frontPkg, store.value())
    }

    private fun apply() {
        ui.removeCallbacks(rampStep)
        val target = targetRadius()
        if (rampMs <= 0) {
            appliedRadius = target
            pushRadius(target)
        } else {
            ui.post(rampStep)
        }
        render()
    }

    /** 1 フレームぶん目標へ近づける */
    private val rampStep = object : Runnable {
        override fun run() {
            val target = targetRadius()
            if (appliedRadius == target) return
            val steps = max(1, rampMs / 16)
            val delta = max(1, abs(target - appliedRadius) / steps)
            appliedRadius =
                if (target > appliedRadius) min(target, appliedRadius + delta)
                else max(target, appliedRadius - delta)
            pushRadius(appliedRadius)
            if (appliedRadius != target) ui.postDelayed(this, 16)
        }
    }

    private fun pushRadius(r: Int) {
        dialog?.window?.setBackgroundBlurRadius(r)
    }

    // ------------------------------------------------------------------ veil

    private fun buildVeil() {
        val d = Dialog(this, R.style.VeilDialog)
        d.setCancelable(false)
        d.setContentView(FrameLayout(this))
        val w = d.window ?: run { Log.e(TAG, "dialog.window is null"); return }
        w.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // この 2 つは付けない。付けると入力を奪われる。
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
            alpha = 1.0f   // 下げるとブラーが減衰する
        }
        // ぼかしの上に描かれる。完全な透明にはしない。
        w.setBackgroundDrawable(ColorDrawable(0x01000000))
        runCatching { d.show() }
            .onSuccess { dialog = d }
            .onFailure { e -> Log.e(TAG, "veil failed: $e") }
    }

    private val blurListener = Consumer<Boolean> { enabled ->
        ui.post { Log.i(TAG, "isCrossWindowBlurEnabled -> $enabled"); render() }
    }

    // ------------------------------------------------------------------ 操作

    private val ctl = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { i?.let { handleCommand(it) } }
    }

    fun handleCommand(i: Intent) = ui.post {
        when (i.getStringExtra("cmd")) {
            "reset", "toggle" -> { requestReset(); return@post }
            "stop" -> { disableSelf(); return@post }
        }
        // 検証用。1 日待たずに解除の権利を戻す
        if (i.getBooleanExtra("unlock", false)) {
            store.unlockReset()
            Log.i(TAG, "手動リセットの権利を戻した（検証用）")
            toast("解除の権利を戻しました")
        }
        i.getStringExtra("mode")?.let { auto = it != "manual" }
        if (i.hasExtra("ramp")) rampMs = max(0, i.getIntExtra("ramp", DEFAULT_RAMP_MS))

        // 検証用。待たずに任意の消費量へ飛ばす
        if (i.hasExtra("c")) {
            store.override(i.getIntExtra("c", 0).toDouble())
            auto = true
        }
        // カーブの調整
        // 到達時間を分で指定。猶予は 30% で連動する
        if (i.hasExtra("ytmin")) {
            settings.setFullMinutes(Targets.YOUTUBE, i.getIntExtra("ytmin", VeilSettings.DEFAULT_YOUTUBE_MIN))
            Log.i(TAG, "YouTube の到達時間を ${settings.fullMinutesFor(Targets.YOUTUBE)} 分に変更")
        }
        if (i.hasExtra("xmin")) {
            settings.setFullMinutes(Targets.X, i.getIntExtra("xmin", VeilSettings.DEFAULT_X_MIN))
            Log.i(TAG, "X の到達時間を ${settings.fullMinutesFor(Targets.X)} 分に変更")
        }
        if (i.hasExtra("enabled")) {
            settings.enabled = i.getBooleanExtra("enabled", true)
            Log.i(TAG, "機能を ${if (settings.enabled) "有効" else "無効"} に変更")
        }
        if (i.hasExtra("k10")) Curve.k = max(1, i.getIntExtra("k10", 20)) / 10.0

        // 手動での半径指定。auto を切って使う
        if (i.hasExtra("r")) {
            manualRadius = min(MAX_RADIUS, max(0, i.getIntExtra("r", 0)))
            auto = false
        }
        if (i.getBooleanExtra("stop", false)) { disableSelf(); return@post }
        apply()
        Log.i(TAG, status())
    }

    /**
     * タイルと通知の「ぼかしを解除」。消費量を 0 に戻す。
     *
     * **1 日 1 回まで。** C のリセットは強い救済なので回数を絞っている。
     * 使ったこと自体が記録に残り、毎日使うようならカーブが厳しすぎるという
     * 判断材料になる（R-02 の観測）。
     */
    fun requestReset() = ui.post {
        if (store.useReset()) {
            auto = true
            manualRadius = 0
            apply()
            val msg = "ぼかしをリセットしました。解除は1日1回のみです"
            Log.i(TAG, "手動リセット実行: $msg")
            toast(msg)
        } else {
            val mins = store.minutesUntilResetAvailable()
            val msg = "本日の解除は使用済みです。あと${mins / 60}時間${mins % 60}分で回復します"
            Log.i(TAG, "手動リセット拒否: $msg")
            toast(msg)
            render()
        }
    }

    /** 今日まだ解除を使っていないか。タイルと通知の表示に使う */
    fun resetAvailable(): Boolean = store.canReset()

    /** 機能が有効か */
    fun isEnabled(): Boolean = settings.enabled

    /** 設定画面から変更されたときに呼ぶ */
    fun reloadSettings() = ui.post {
        Log.i(
            TAG,
            "設定を再読込: YouTube ${settings.fullMinutesFor(Targets.YOUTUBE)}分 / " +
                "X ${settings.fullMinutesFor(Targets.X)}分 / enabled=${settings.enabled}"
        )
        apply()
    }

    /** 画面に出すための現在値 */
    fun consumptionSeconds(): Int = store.value().toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- 表示

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Veil コントロール", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun render() {
        postNotification()
        tile?.sync()
    }

    private fun postNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val r = targetRadius()
        val mins = store.minutes()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            !settings.enabled -> "停止中"
            !auto -> "手動  r $r"
            r > 0 -> "ぼかし中  r $r"
            store.accumulating -> "計測中  ${"%.1f".format(mins)}分"
            else -> "待機中  ${"%.1f".format(mins)}分"
        }
        val canReset = store.canReset()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText(
                if (!settings.enabled) "ぼかしは無効。視聴時間の記録だけ続けています"
                else "${Targets.labelOf(frontPkg)} ・ 強度 ${(settings.strengthFor(frontPkg, store.value()) * 100).roundToInt()}%" +
                    " ・ 解除 ${if (canReset) "残り1回" else "本日使用済"}"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .let { b ->
                if (settings.enabled) {
                    b.addAction(cmdAction(if (canReset) "ぼかしを解除" else "解除は明日まで待つ", "reset"))
                } else b
            }
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

    fun status(): String {
        val c = store.value()
        return "pkg=%s C=%.0f (%.1f分) p=%.2f r=%d auto=%s acc=%s shade=%s screen=%s blur=%s".format(
            frontPkg ?: "-", c, c / 60.0, settings.strengthFor(frontPkg, c), targetRadius(),
            auto, store.accumulating, shadeFront, screenOn, wm.isCrossWindowBlurEnabled
        ) + " reset=" + (if (store.canReset()) "可" else "本日使用済") +
            " enabled=" + settings.enabled +
            " 到達=" + settings.fullMinutesFor(frontPkg) + "分"
    }

    private fun logEnvironment() {
        val b = wm.currentWindowMetrics.bounds
        Log.i(
            TAG,
            "device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "display=${b.width()}x${b.height()} " +
                "到達 YouTube=${settings.fullMinutesFor(Targets.YOUTUBE)}分 " +
                "X=${settings.fullMinutesFor(Targets.X)}分 k=${Curve.k} " +
                "blurEnabled=${wm.isCrossWindowBlurEnabled}"
        )
    }
}
