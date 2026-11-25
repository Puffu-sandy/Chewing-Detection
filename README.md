# 早餐咀嚼小幫手 (BreakfastBuddyApp)

專為 Android 平板與手機設計的早餐輔助 App，透過前置鏡頭即時偵測小朋友的咀嚼與送食動作，協助養成專心吃早餐的好習慣。

## 核心功能

1. **平板雙欄 / 手機直向響應式介面**：在平板橫放時左側 68% 播放 YouTube 影片，右側 32% 顯示鏡頭偵測狀態與家長控制項。
2. **即時咀嚼偵測**：結合 Google ML Kit Face Detection (臉部輪廓) 追蹤嘴唇開合與下顎起伏波峰。
3. **防假裝空咬（偵測新食物進入口內）**：結合 ML Kit Pose Detection (手部靠近嘴部偵測) + 嘴部區域亮度遮擋變化 + 張口進食動作，確認真的有送食物進嘴巴。每口食物設有合理咀嚼上限，若只動嘴巴沒進新食物會暫停影片提醒。
4. **漸進式延長暫停時間**：每次忘記咀嚼，強制等待冷卻時間會依序延長（例如 `5秒 → 10秒 → 15秒 → 25秒 → 35秒 → 50秒`，上限 60 秒）。必須等倒數結束且重新開始有效咀嚼，影片才會繼續播放。
5. **語音與視覺雙重提醒**：暫停時跳出全螢幕提醒遮罩，並可播放繁體中文語音提醒。

## 如何打包成可安裝的 APK 檔

### 方法一：使用 Android Studio（本機）
1. 解壓縮此專案資料夾，使用 **Android Studio** 選擇 `Open` 開啟。
2. 等待 Gradle Sync 自動下載相依套件。
3. 點選上方選單 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`，或在終端機執行：
   ```bash
   ./gradlew assembleDebug
   ```
4. 產出的安裝檔位於：`app/build/outputs/apk/debug/app-debug.apk`，傳到平板直接點擊安裝即可。

### 方法二：免裝 Android Studio（透過 GitHub Actions 雲端編譯）
1. 在 GitHub 建立一個新 Repository，將此專案所有檔案（包含 `.github/workflows/build-apk.yml`）上傳。
2. 進入 GitHub 專案頁面的 **Actions** 分頁，等待 `Build Android APK` 執行完成。
3. 在下方 **Artifacts** 區塊直接下載 `BreakfastBuddy-debug-apk`，解壓縮後將 `.apk` 傳到平板安裝即可。

## 使用小技巧
- 將平板立在餐桌正前方約 40–70 公分處，保持臉部與肩膀光線充足，前鏡頭同時看得到臉部與拿湯匙的手，防假裝空咬的準確度會最高。
- 若使用特殊餐具或喝牛奶時系統未判讀到進食動作，家長可點一下右側的 **「手動記一口」** 輔助按鈕。
