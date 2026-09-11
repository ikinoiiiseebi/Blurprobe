package dev.veil.blurprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通知のボタンと adb の両方から叩かれる操作の受け口。
 *
 *   adb shell am broadcast -n dev.veil.blurprobe/.CtlReceiver --ei r 63
 *   adb shell am broadcast -a dev.veil.blurprobe.CTL -f 0x01000000 --ei r 63
 */
class CtlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        val svc = ProbeService.instance
        Log.i(
            ProbeService.TAG,
            "CtlReceiver extras=${intent.extras?.keySet()?.joinToString()} service=${svc != null}"
        )
        if (svc == null) {
            Log.w(ProbeService.TAG, "サービスが動いていません。ユーザー補助をオンにしてください")
            return
        }
        svc.handleCommand(intent)
    }
}
