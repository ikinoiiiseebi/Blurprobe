package dev.veil.blurprobe

import android.content.Context
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 対象アプリと、それぞれの半径の上限。
 *
 * 判読不能になる半径が実測でアプリごとに 2.5 倍違ったため、上限だけを分けて持つ。
 * 消費量 C そのものは共有する（アプリを跨いだ代償行動を封じるため）。
 */
object Targets {
    const val YOUTUBE = "com.google.android.youtube"
    const val X = "com.twitter.android"

    /** Pixel 9 Pro XL / 1344px 幅での実測値 */
    val R_MAX = mapOf(
        YOUTUBE to 40,
        X to 16,
    )

    fun isTarget(pkg: String?): Boolean = pkg != null && R_MAX.containsKey(pkg)
    fun rMaxOf(pkg: String?): Int = R_MAX[pkg] ?: 0
    fun labelOf(pkg: String?): String = when (pkg) {
        YOUTUBE -> "YouTube"
        X -> "X"
        else -> "—"
    }
}

/**
 * 消費量から 0〜1 の強度を出す曲線。
 *
 * σ は半径に線形なので体感の劣化は前半に集中する。k = 1.0（線形）だと
 * 序盤で一気に潰れて後半は何も起きない。k = 2.0 前後で初めて「じわじわ効く」。
 */
object Curve {
    /** 猶予。ここまでは一切干渉しない */
    var g = 180.0

    /** 上限に到達する消費量 */
    var t = 1200.0

    /** 曲線の指数 */
    var k = 2.0

    fun p(c: Double): Double = when {
        c <= g -> 0.0
        c >= t -> 1.0
        else -> ((c - g) / (t - g)).pow(k)
    }

    /** 対象アプリでの半径。対象外なら 0。 */
    fun radiusFor(pkg: String?, c: Double): Int =
        if (!Targets.isTarget(pkg)) 0
        else (Targets.rMaxOf(pkg) * p(c)).roundToInt()
}

/**
 * 消費量 C の蓄積・減衰・日次リセットを引き受ける。
 *
 * サービスが死んでも端末を再起動しても値が続くよう SharedPreferences に持つ。
 * Room はログを取り始める M2 まで入れない。
 *
 * 減衰は「読むときに経過時間ぶんまとめて適用する」方式。
 * アプリが動いていない間の経過も正しく反映される。
 */
class ConsumptionStore(context: Context) {

    companion object {
        private const val PREFS = "veil"
        private const val KEY_C = "c"
        private const val KEY_AT = "at"
        private const val KEY_DAY = "day"
        private const val KEY_RESET_DAY = "resetDay"

        /** 減衰の半減期（秒）。対象アプリから離れている間に効く */
        const val HALF_LIFE_SEC = 5400.0

        /** 一日の区切り。深夜の利用を前日ぶんとして扱うため 04:00 起点 */
        const val DAY_START_HOUR = 4

        /** 書き込み頻度を抑えるための間隔 */
        private const val FLUSH_INTERVAL_MS = 10_000L

        /** その時刻が属する「Veil の一日」。04:00 起点で数える */
        fun veilDay(nowMs: Long): Long {
            val offset = TimeZone.getDefault().getOffset(nowMs)
            return (nowMs + offset - DAY_START_HOUR * 3_600_000L) / 86_400_000L
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var c: Double = prefs.getFloat(KEY_C, 0f).toDouble()
    private var at: Long = prefs.getLong(KEY_AT, System.currentTimeMillis())
    private var day: Long = prefs.getLong(KEY_DAY, veilDay(System.currentTimeMillis()))

    /**
     * 手動リセットを使った日。1 日 1 回までという制限の記録。
     *
     * C を 0 に戻すのは強い救済なので回数を絞る。使ったこと自体が残るので、
     * 毎日使っているなら「カーブが厳しすぎる」という判断材料になる（R-02 の観測）。
     */
    private var resetDay: Long = prefs.getLong(KEY_RESET_DAY, -1L)

    private var lastFlush = 0L

    /** 対象アプリを見ている間は true。この間は減衰させない */
    var accumulating = false
        private set

    /** 日付が変わっていれば 0 に戻す。戻したら true */
    private fun rolloverIfNeeded(now: Long): Boolean {
        val d = veilDay(now)
        if (d != day) {
            c = 0.0
            day = d
            at = now
            flush(force = true)
            return true
        }
        return false
    }

    /** 経過時間ぶんの減衰を適用して現在値を返す */
    fun value(now: Long = System.currentTimeMillis()): Double {
        rolloverIfNeeded(now)
        if (!accumulating) {
            val elapsed = max(0L, now - at) / 1000.0
            if (elapsed > 0) {
                c *= 0.5.pow(elapsed / HALF_LIFE_SEC)
                at = now
                flush()
            }
        }
        return c
    }

    /** 対象アプリに入った。まず滞留ぶんを減衰させてから蓄積へ切り替える */
    fun startAccumulating(now: Long = System.currentTimeMillis()) {
        if (accumulating) return
        value(now)
        accumulating = true
        at = now
    }

    /** 対象アプリから離れた */
    fun stopAccumulating(now: Long = System.currentTimeMillis()) {
        if (!accumulating) return
        accumulating = false
        at = now
        flush(force = true)
    }

    /** 1 秒ぶんの加算。滞在時間そのものが消費量になる */
    fun tick(seconds: Double = 1.0, now: Long = System.currentTimeMillis()) {
        if (!accumulating) return
        rolloverIfNeeded(now)
        c += seconds
        at = now
        flush()
    }

    // ------------------------------------------------------------- 手動リセット

    /** 今日まだ使っていなければ true */
    fun canReset(now: Long = System.currentTimeMillis()): Boolean {
        rolloverIfNeeded(now)
        return resetDay != veilDay(now)
    }

    /** 消費量を 0 に戻す。使えたら true、今日すでに使っていたら false */
    fun useReset(now: Long = System.currentTimeMillis()): Boolean {
        if (!canReset(now)) return false
        c = 0.0
        at = now
        resetDay = veilDay(now)
        flush(force = true)
        return true
    }

    /** 検証用。今日の使用済みフラグを消す */
    fun unlockReset() {
        resetDay = -1L
        flush(force = true)
    }

    /** 次にリセットが使えるようになるまでの時間（分）。使えるなら 0 */
    fun minutesUntilResetAvailable(now: Long = System.currentTimeMillis()): Int {
        if (canReset(now)) return 0
        val offset = java.util.TimeZone.getDefault().getOffset(now)
        val shifted = now + offset - DAY_START_HOUR * 3_600_000L
        val nextDayStart = ((shifted / 86_400_000L) + 1) * 86_400_000L
        return max(0L, (nextDayStart - shifted) / 60_000L).toInt()
    }

    /** 検証用。待たずに任意の消費量へ飛ばす */
    fun override(newValue: Double, now: Long = System.currentTimeMillis()) {
        c = max(0.0, newValue)
        at = now
        day = veilDay(now)
        flush(force = true)
    }

    private fun flush(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < FLUSH_INTERVAL_MS) return
        lastFlush = now
        prefs.edit()
            .putFloat(KEY_C, c.toFloat())
            .putLong(KEY_AT, at)
            .putLong(KEY_DAY, day)
            .apply()
    }

    fun persistNow() = flush(force = true)

    /** 表示用。今日リセットを使ったかどうか */
    fun resetUsedToday(now: Long = System.currentTimeMillis()): Boolean = !canReset(now)

    /** 表示用。消費量を「何分ぶん」として読めるようにする */
    fun minutes(now: Long = System.currentTimeMillis()): Double = value(now) / 60.0
}
