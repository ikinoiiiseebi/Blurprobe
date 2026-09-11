package dev.veil.blurprobe

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * クイック設定タイル。**1 日 1 回だけ**ぼかしを解除できる。
 *
 * 解除は消費量 C を 0 に戻す強い救済なので回数を絞っている。
 * 使ったこと自体が記録に残り、毎日使うようならカーブが厳しすぎるという判断材料になる。
 *
 * 初回だけ手動で追加が必要:
 *   通知シェードを下ろす → 編集 → 「Veil」をドラッグして配置
 */
class VeilTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        ProbeService.tile = this
        sync()
    }

    override fun onStopListening() {
        ProbeService.tile = null
        super.onStopListening()
    }

    override fun onClick() {
        val svc = ProbeService.instance ?: run { sync(); return }
        // 使えたかどうかの案内はサービス側がトーストで出す
        svc.requestReset()
        sync()
    }

    fun sync() {
        val svc = ProbeService.instance
        qsTile?.apply {
            when {
                svc == null -> {
                    state = Tile.STATE_UNAVAILABLE
                    subtitle = "未起動"
                }
                !svc.resetAvailable() -> {
                    // 今日はもう使えない。押しても案内が出るだけ
                    state = Tile.STATE_INACTIVE
                    subtitle = "本日使用済"
                }
                svc.currentRadius > 0 -> {
                    state = Tile.STATE_ACTIVE
                    subtitle = "解除できます"
                }
                else -> {
                    state = Tile.STATE_ACTIVE
                    subtitle = "解除 残り1回"
                }
            }
            updateTile()
        }
    }
}
