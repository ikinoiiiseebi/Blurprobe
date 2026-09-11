package dev.veil.blurprobe

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.math.roundToInt

/**
 * クイック設定タイル。画面がぼけて操作しづらいときの、adb を使わない最短の復帰経路。
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
        svc.toggleVeil()
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
                svc.currentRadius > 0 -> {
                    state = Tile.STATE_ACTIVE
                    subtitle = "r ${svc.currentRadius}"
                }
                else -> {
                    state = Tile.STATE_INACTIVE
                    subtitle = "解除中"
                }
            }
            updateTile()
        }
    }
}
