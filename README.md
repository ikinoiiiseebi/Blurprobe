# Veil

ショート動画の消費量に応じて画面へ段階的なブラーをかけ、視聴を打ち切るのではなく
**「見続ける価値」を逓減させる**Android 常駐アプリ。

個人利用専用。Play ストアには出さず、自分の端末に ADB で直接入れる。

- 対象端末: Pixel 9 Pro XL (1344×2992 / 3.0x / Android 16)
- 対象アプリ: YouTube Shorts、X
- minSdk 31（`setBackgroundBlurRadius` が API 31 から）
- **`INTERNET` 権限を宣言しない。** 一切通信しない

## 設計の背景

普通のスクリーンタイムアプリは「ブロックする」か「数字を見せる」かのどちらかで、
前者はリアクタンスを招き、後者はコストを伴わない。

Veil は**累進課金型**を狙う。見続けるほど、見ている対象そのものが失われていく。
消費と代償が同じリソース（画面の可読性）で釣り合う構造。

最初の数分は一切干渉しない。一時的な欲そのものは否定しない設計。

## 状態

**Phase 0（方式検証）完了。** 実機で以下を確認済み。

| 検証 | 結果 |
|---|---|
| V-01 動画レイヤー上でのブラー描画 | 通過 |
| V-02 ぼけた状態でのタッチ透過 | 通過 |
| V-03 判読限界 | Shorts r=40 / X r=16。**アプリ別に上限を持つ** |
| V-04 描画負荷 | スクロールのカクつきなし |

詳細は [`docs/phase0-findings.md`](docs/phase0-findings.md)。
要件は [`docs/requirements.md`](docs/requirements.md)。

**Phase 1（本実装）着手前。** 現在のコードは検証用アプリで、
ブラーの描画と復帰経路のみが実装されている。

## ビルドと導入

Android Studio は不要。コマンドラインで完結する。

```powershell
# 初回のみ。Android Studio 同梱の JDK を使う
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", "User")
```

```powershell
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

導入後、端末側で以下を設定する。

1. アプリを開いて「通知を許可」
2. 設定 → ユーザー補助 → **BlurProbe をオン**（これが起動スイッチ）
3. 通知シェード → 編集 → タイル **「Veil」** を配置（緊急解除用）

## 操作

検証中は adb から制御する。

```powershell
adb shell am broadcast -n dev.veil.blurprobe/.CtlReceiver --ei r 40
```

| 引数 | 意味 |
|---|---|
| `--ei r <0-250>` | ブラー半径 |
| `--ei ramp <ms>` | 遷移時間。既定 250 |
| `--ei ttl <sec>` | 無操作での自動解除。`0` で無効 |
| `--ez shade <bool>` | 通知シェード連動 |
| `--es cmd toggle` | 入切 |
| `--ez stop true` | サービス停止 |

ログは `adb logcat -s BlurProbe`。

## 安全装置

画面がぼけたまま戻せなくなる事故を防ぐため、復帰経路を三重に持つ。

1. **クイック設定タイル**「Veil」— ワンタップ解除。最短経路
2. **常駐通知**の「解除」ボタン
3. **無操作 10 分**での自動解除

サービスを止めればオーバーレイは必ず破棄される。
