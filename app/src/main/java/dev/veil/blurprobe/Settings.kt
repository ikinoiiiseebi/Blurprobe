package dev.veil.blurprobe

import android.content.Context
import kotlin.math.roundToInt

/**
 * 利用者が触れる設定。
 *
 * 触れるつまみは**アプリごとの「最大ぼかしに到達するまでの時間」**だけ。
 * 猶予とカーブの形はそこから比例で決まるので、スライダーを動かすと
 * 全体の速さが変わり、形は保たれる。
 *
 * アプリ別に分けているのは、判読限界が実測で 2.5 倍違ったのと同じ理由で、
 * 「もう十分見た」と感じる時間も媒体によって違うため。
 */
class VeilSettings(context: Context) {

    companion object {
        private const val PREFS = "veil"
        private const val KEY_ENABLED = "enabled"

        const val MIN_MINUTES = 3
        const val MAX_MINUTES = 10

        /** 既定の到達時間。X のほうが短いのは、テキストのほうが早く飽和するため */
        const val DEFAULT_YOUTUBE_MIN = 10
        const val DEFAULT_X_MIN = 7

        /** 機能を止めるのに必要な長押し時間。やめるのは苦痛に、戻るのは楽に */
        const val DISABLE_HOLD_MS = 180_000L

        private fun keyOf(pkg: String) = when (pkg) {
            Targets.YOUTUBE -> "fullMin_yt"
            Targets.X -> "fullMin_x"
            else -> "fullMin_other"
        }

        private fun defaultOf(pkg: String) = when (pkg) {
            Targets.X -> DEFAULT_X_MIN
            else -> DEFAULT_YOUTUBE_MIN
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 機能そのものの有効・無効 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    // ------------------------------------------------------------ アプリ別の速さ

    fun fullMinutesFor(pkg: String?): Int {
        val p = pkg ?: return DEFAULT_YOUTUBE_MIN
        return prefs.getInt(keyOf(p), defaultOf(p)).coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    fun setFullMinutes(pkg: String, minutes: Int) {
        prefs.edit()
            .putInt(keyOf(pkg), minutes.coerceIn(MIN_MINUTES, MAX_MINUTES))
            .apply()
    }

    fun fullSecondsFor(pkg: String?): Double = fullMinutesFor(pkg) * 60.0

    /** 現在の設定での半径 */
    fun radiusFor(pkg: String?, c: Double): Int =
        Curve.radiusFor(pkg, c, fullSecondsFor(pkg))

    /** 現在の設定での強度 0〜1 */
    fun strengthFor(pkg: String?, c: Double): Double =
        if (!Targets.isTarget(pkg)) 0.0 else Curve.p(c, fullSecondsFor(pkg))

    // ------------------------------------------------------- 画面に出すプレビュー

    /** 干渉が始まるまでの秒数 */
    fun graceSecondsFor(pkg: String): Int = Curve.graceOf(fullSecondsFor(pkg)).roundToInt()

    /** 体感でぼけ始める目安。強度 20% に達する時刻 */
    fun noticeableSecondsFor(pkg: String): Int = Curve.noticeableAt(fullSecondsFor(pkg)).roundToInt()

    fun fullSecondsIntFor(pkg: String): Int = fullMinutesFor(pkg) * 60
}

/** 秒を読みやすく */
fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}時間${m}分"
        m > 0 && s > 0 -> "${m}分${s}秒"
        m > 0 -> "${m}分"
        else -> "${s}秒"
    }
}
