package dev.veil.blurprobe

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 判定に使う設定。**再ビルドなしで直せるよう外部ファイルに置ける。**
 *
 * YouTube の更新でビュー ID は必ず変わる。壊れたときにアプリを作り直さずに済むよう、
 * 端末の以下に JSON を置くと読み込む。無ければコード内の既定値を使う。
 *
 *   /sdcard/Android/data/dev.veil.blurprobe/files/detection.json
 *
 * 書き込みは adb から:
 *   adb push detection.json /sdcard/Android/data/dev.veil.blurprobe/files/
 */
class DetectionConfig private constructor(
    /** Shorts 画面にだけ存在するビュー ID。どれか 1 つでも見つかれば Shorts */
    val shortsViewIds: List<String>,
    /** 画面高の何割を超えるスクロールを「1 枚送り」とみなすか */
    val pageScrollRatio: Double,
    /** スワイプ 1 回を消費量に何秒ぶんとして足すか */
    val swipeWeight: Int,
) {
    companion object {
        const val FILE_NAME = "detection.json"

        private val DEFAULT_IDS = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_progress_bar",
            "com.google.android.youtube:id/reel_watch_player",
            "com.google.android.youtube:id/shorts_container",
        )

        private const val DEFAULT_PAGE_RATIO = 0.70
        private const val DEFAULT_SWIPE_WEIGHT = 3

        fun load(context: Context): DetectionConfig {
            val f = File(context.getExternalFilesDir(null), FILE_NAME)
            if (!f.exists()) {
                Log.i(ProbeService.TAG, "detection.json なし。既定値を使う（${f.absolutePath}）")
                return DetectionConfig(DEFAULT_IDS, DEFAULT_PAGE_RATIO, DEFAULT_SWIPE_WEIGHT)
            }
            return try {
                val o = JSONObject(f.readText())
                val ids = o.optJSONArray("shortsViewIds")?.let { a: JSONArray ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: DEFAULT_IDS
                val cfg = DetectionConfig(
                    ids,
                    o.optDouble("pageScrollRatio", DEFAULT_PAGE_RATIO),
                    o.optInt("swipeWeight", DEFAULT_SWIPE_WEIGHT),
                )
                Log.i(ProbeService.TAG, "detection.json を読み込んだ: ID ${ids.size}件 ratio=${cfg.pageScrollRatio}")
                cfg
            } catch (e: Exception) {
                Log.w(ProbeService.TAG, "detection.json の読み込みに失敗。既定値を使う: $e")
                DetectionConfig(DEFAULT_IDS, DEFAULT_PAGE_RATIO, DEFAULT_SWIPE_WEIGHT)
            }
        }
    }
}

/**
 * YouTube が前面のとき、それが Shorts なのか通常の動画・フィードなのかを判定する。
 *
 * 二層にしてある。
 *
 *   一次（ヒューリスティック）… **1 枚送りのスクロール**を見る。Shorts は 1 スワイプで
 *       画面 1 枚ぶん飛ぶので、スクロール量が画面高に近い。ビュー ID に依存しないため
 *       YouTube の更新で壊れない
 *   二次（ビュー ID）… Shorts 画面にしか無い ID を探す。確実だが更新で壊れる
 *
 * どちらか一方でも成立すれば Shorts とみなす。**ID が腐っても一次判定だけで動く。**
 * 両者が食い違ったら記録しておき、ID の劣化に気づけるようにする。
 */
class ShortsDetector(
    private val config: DetectionConfig,
    private val screenHeightPx: Int,
) {
    /** 1 枚送りとみなすスクロール量の下限 */
    private val pageThreshold = (screenHeightPx * config.pageScrollRatio).toInt()

    /** 現在 Shorts 表示中とみなしているか */
    var inShorts = false
        private set

    /** 一次判定が最後に成立した時刻 */
    private var lastPageScrollAt = 0L

    /** 直近の判定根拠。ログ用 */
    var lastReason = "—"
        private set

    /** 判定が食い違った回数。ID の劣化を検知する */
    var mismatchCount = 0
        private set

    /**
     * 1 枚送りのスクロールが起きた「スワイプ」を数える。
     *
     * 通常のフィードは指の動きぶんだけ連続的に動くので、この閾値には届かない。
     */
    fun onScrolled(deltaY: Int, now: Long): Boolean {
        if (abs(deltaY) < pageThreshold) return false
        lastPageScrollAt = now
        if (!inShorts) {
            inShorts = true
            lastReason = "スクロール量 ${abs(deltaY)}px（閾値 $pageThreshold）"
        }
        return true
    }

    /**
     * ビュー ID を探して裏取りする。1 秒に 1 回程度でよい。
     *
     * @param root 取れなければ null。その場合は一次判定の結果を維持する
     */
    fun refreshByViewId(root: AccessibilityNodeInfo?, now: Long) {
        if (root == null) return
        val found = config.shortsViewIds.any { id ->
            runCatching { root.findAccessibilityNodeInfosByViewId(id).isNotEmpty() }
                .getOrDefault(false)
        }

        // 一次判定がまだ生きているか（最後の 1 枚送りから 10 秒以内）
        val heuristicFresh = now - lastPageScrollAt < 10_000L

        if (found != heuristicFresh && (found || heuristicFresh)) {
            mismatchCount++
            if (mismatchCount % 20 == 1) {
                Log.w(
                    ProbeService.TAG,
                    "判定が食い違った（${mismatchCount}回目） ID=$found ヒューリスティック=$heuristicFresh"
                )
            }
        }

        val next = found || heuristicFresh
        if (next != inShorts) {
            inShorts = next
            lastReason = if (found) "ビューID一致" else if (heuristicFresh) "1枚送りが継続中" else "どちらも不成立"
            Log.i(ProbeService.TAG, "Shorts判定 -> $inShorts（$lastReason）")
        }
    }

    /** YouTube から離れた。状態を捨てる */
    fun reset() {
        inShorts = false
        lastPageScrollAt = 0L
        lastReason = "—"
    }

    fun swipeWeight(): Int = config.swipeWeight
}
