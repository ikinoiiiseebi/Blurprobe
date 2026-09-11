package dev.veil.blurprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 権限を整える入口。サービスの起動・停止は「ユーザー補助」の ON/OFF で行う。
 */
class MainActivity : Activity() {

    private lateinit var envText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1114.toInt())
            setPadding(dp(22), dp(46), dp(22), dp(34))
        }

        root.addView(heading("BlurProbe"))
        root.addView(
            body(
                "V-01 の検証用。全画面のブラーウィンドウを 1 枚出すだけのアプリです。\n\n" +
                    "ユーザー補助をオンにすると常駐し、フローティングパネルが出ます。" +
                    "YouTube か X を開くと消費量が溜まり、猶予を超えるとぼけ始めます。"
            )
        )

        envText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(0xFF8FD6BB.toInt())
            setLineSpacing(0f, 1.35f)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF171B1F.toInt())
            }
        }
        root.addView(envText, marginTop(dp(18)))

        root.addView(sectionLabel("1 ・ 常駐を有効にする"), marginTop(dp(26)))
        root.addView(
            body(
                "「ユーザー補助を開く」→ ダウンロードしたアプリ → BlurProbe → オン。\n" +
                    "オンにした瞬間にパネルが出ます。オフにすれば完全に停止します。"
            )
        )
        root.addView(action("ユーザー補助を開く", primary = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(sectionLabel("2 ・ 復帰の経路を用意する"), marginTop(dp(26)))
        root.addView(
            body(
                "画面がぼけたまま操作しづらくなったときの戻し方を、先に確保しておきます。\n\n" +
                    "・常駐通知の「解除」ボタン\n" +
                    "・クイック設定タイル「Veil」\n\n" +
                    "タイルは初回だけ手動で追加が必要です。通知シェードを下ろす → 編集 → " +
                    "「Veil」を上のエリアにドラッグ。"
            )
        )
        root.addView(action("通知を許可") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })

        root.addView(sectionLabel("3 ・ 比較用（任意）"), marginTop(dp(26)))
        root.addView(
            body(
                "パネルの TYPE ボタンで通常のオーバーレイと切り替えて挙動を比較できます。" +
                    "そちらを試す場合だけ、以下の権限が必要です。"
            )
        )
        root.addView(action("他のアプリの上に重ねて表示") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        root.addView(sectionLabel("4 ・ 確認する項目"), marginTop(dp(26)))
        root.addView(
            body(
                "3分まで  何も起きない（猶予帯）\n" +
                    "8分ごろ  はっきりぼけている\n" +
                    "離脱90分 ぼけが約半分に戻る\n\n" +
                    "待たずに確かめるなら消費量を直接入れられます。\n" +
                    "adb shell am broadcast -n dev.veil.blurprobe/.CtlReceiver --ei c 600\n\n" +
                    "adb logcat -s BlurProbe  でログが追えます。"
            )
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E1114.toInt())
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        val wm = getSystemService(WindowManager::class.java)
        val b = wm.currentWindowMetrics.bounds
        val d = resources.displayMetrics.density
        envText.text = buildString {
            append("${Build.MODEL}   Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("display   ${b.width()} x ${b.height()} px @ ${d}x\n")
            append("logical   ${(b.width() / d).roundToInt()} x ${(b.height() / d).roundToInt()} dp\n")
            append("blur      ${if (wm.isCrossWindowBlurEnabled) "有効" else "無効"}\n")
            append(veilState())
            append("常駐      ${if (serviceEnabled()) "オン" else "オフ ← 手順1へ"}\n")
            append("通知      ${if (notifGranted()) "許可済" else "未許可 ← 手順2へ"}\n")
            append("overlay   ${if (Settings.canDrawOverlays(this@MainActivity)) "許可済" else "未許可（比較用のみ必要）"}")
        }
    }

    private fun notifGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }

    /** サービスが動いていれば消費量とカーブの現在値を出す */
    private fun veilState(): String {
        val svc = ProbeService.instance ?: return "消費量    —（サービス未起動）\n"
        return "状態      ${svc.status()}\n" +
            "カーブ    G=${Curve.g.toInt()} T=${Curve.t.toInt()} k=${Curve.k}\n" +
            "上限      Shorts=${Targets.rMaxOf(Targets.YOUTUBE)} X=${Targets.rMaxOf(Targets.X)}\n"
    }

    private fun serviceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.id.orEmpty().contains(packageName) }
    }

    // ------------------------------------------------------------- view help

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 27f
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
        setTextColor(0xFF35B6C0.toInt())
        setPadding(0, 0, 0, dp(7))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 13.5f
        setTextColor(0xFFA8B0B6.toInt())
        setLineSpacing(0f, 1.45f)
        setPadding(0, dp(9), 0, 0)
    }

    private fun action(label: String, primary: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(if (primary) 0xFF07171A.toInt() else Color.WHITE)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(if (primary) 0xFF35B6C0.toInt() else 0xFF22272C.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun marginTop(px: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = px }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
