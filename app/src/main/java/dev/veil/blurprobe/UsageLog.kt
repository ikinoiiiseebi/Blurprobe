package dev.veil.blurprobe

import android.content.Context
import kotlin.math.abs

/**
 * 対象アプリの視聴時間を日別に記録する。
 *
 * Room は使わない。**1 日 1 アプリあたり整数ひとつ**しか要らないので、
 * SharedPreferences のキーを日付ごとに分けるだけで足りる。
 *
 * ここに積むのは**減衰しない生の秒数**。消費量 C とは別物で、
 * C は「今どれだけぼかすか」を決める値、こちらは「実際に何分見たか」の記録。
 */
class UsageLog(context: Context) {

    companion object {
        private const val PREFS = "veil_log"
        private const val KEY_PRUNED = "prunedDay"

        /** これより古い日別データは捨てる */
        private const val KEEP_DAYS = 120

        private fun shortKey(pkg: String): String = when (pkg) {
            Targets.YOUTUBE -> "yt"
            Targets.X -> "x"
            else -> "other"
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 1 秒ぶんの記録 */
    fun add(pkg: String, seconds: Int = 1, now: Long = System.currentTimeMillis()) {
        val day = ConsumptionStore.veilDay(now)
        val k = "d_${shortKey(pkg)}_$day"
        val total = "total_${shortKey(pkg)}"
        prefs.edit()
            .putInt(k, prefs.getInt(k, 0) + seconds)
            .putInt(total, prefs.getInt(total, 0) + seconds)
            .apply()
    }

    /** その日の秒数。pkg を省くと対象アプリの合計 */
    fun secondsOn(day: Long, pkg: String? = null): Int =
        if (pkg != null) prefs.getInt("d_${shortKey(pkg)}_$day", 0)
        else Targets.R_MAX.keys.sumOf { prefs.getInt("d_${shortKey(it)}_$day", 0) }

    /**
     * 週ごとの合計。weeksAgo = 0 が今週（今日を含む直近 7 日）、1 が その前の 7 日。
     *
     * 暦の週ではなく「直近 7 日」で切る。週の途中で比べても意味のある数字になるため。
     */
    fun weekSeconds(weeksAgo: Int, pkg: String? = null, now: Long = System.currentTimeMillis()): Int {
        val today = ConsumptionStore.veilDay(now)
        val start = today - (weeksAgo * 7) - 6
        val end = today - (weeksAgo * 7)
        return (start..end).sumOf { secondsOn(it, pkg) }
    }

    /**
     * 先週からの変化率（%）。先週が 0 なら比較できないので null。
     *
     * 負の値が「減った」= 望ましい方向。
     */
    fun weekChangePercent(pkg: String? = null, now: Long = System.currentTimeMillis()): Int? {
        val prev = weekSeconds(1, pkg, now)
        if (prev == 0) return null
        val curr = weekSeconds(0, pkg, now)
        return ((curr - prev) * 100.0 / prev).toInt()
    }

    /** 記録を取り始めてからの合計 */
    fun totalSeconds(pkg: String? = null): Int =
        if (pkg != null) prefs.getInt("total_${shortKey(pkg)}", 0)
        else Targets.R_MAX.keys.sumOf { prefs.getInt("total_${shortKey(it)}", 0) }

    /** 古い日別データを捨てる。1 日 1 回で足りる */
    fun pruneIfNeeded(now: Long = System.currentTimeMillis()) {
        val today = ConsumptionStore.veilDay(now)
        if (prefs.getLong(KEY_PRUNED, -1L) == today) return
        val cutoff = today - KEEP_DAYS
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("d_") }.forEach { key ->
            key.substringAfterLast('_').toLongOrNull()?.let { d ->
                if (d < cutoff) e.remove(key)
            }
        }
        e.putLong(KEY_PRUNED, today).apply()
    }

    /** 変化率を「+18%」「−32%」のように読みやすく。null は比較できない */
    fun formatChange(pct: Int?): String = when {
        pct == null -> "—"
        pct > 0 -> "+$pct%"
        pct < 0 -> "−${abs(pct)}%"
        else -> "±0%"
    }
}
