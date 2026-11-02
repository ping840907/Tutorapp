# FluentTutor AI 🎙️ - 即時語音對答情境任務學習 App

> 基於 **LFM3-Audio (Liquid Foundation Model 3 Audio / FLM3 Audio)** 多模態語音模型，結合端側自適應 VAD、分段流式輸入與任務目標判定小模型的 Android 語音學習應用。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-brightgreen.svg)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-blue.svg)](https://developer.android.com/jetpack/compose)
[![Model](https://img.shields.io/badge/Audio%20Model-LFM3--Audio--2B-orange.svg)](https://liquid.ai)

---

## 🌟 核心特色與亮點

1. **分段進行語音輸入 (Segmented Audio Input via VAD)**
   - 透過端側自適應語音活動檢測器（Adaptive Voice Activity Detector），實時監控環境底噪與說話能量。
   - 用戶說完一句話並停頓 600ms~700ms 時，**自動切片斷句並即時送出**，徹底解決傳統錄音長時間等待與卡頓問題，達到超低延遲回話。

2. **目標導向對話任務 (Mission-Driven Scenarios)**
   - 每個關卡均具備清晰的「任務清單（Sub-goals）」，例如：
     - **咖啡廳點餐**：點燕麥奶拿鐵 ➔ 指定尺寸與冰熱 ➔ 完成結帳付款。
     - **飯店櫃檯入住**：報到登記 ➔ 詢問早餐時段與 Wi-Fi 密碼 ➔ 領取房卡致謝。
     - **日語便利商店 (コンビニ)**：購買便當 ➔ 提出加熱要求 ➔ 溝通塑膠袋與筷子 ➔ 結帳完成。
     - **日語拉麵店 (ラーメン屋)**：點餐 ➔ 指定麵條硬度（バリカタ・かため）➔ 加點/致謝。

3. **打分與過關 / 失敗判定小模型 (Mission Judge Engine)**
   - **🎉 宣布過關 (Mission Passed)**：當用戶在對話中逐一達成所有關鍵子目標時，系統即刻判定過關，頒發 1~3 顆星級榮譽並給予語音流暢度總結報告。
   - **❌ 宣告失敗 (Mission Failed)**：
     - 若用戶連續偏離任務目標（例如在點餐時胡言亂語或聊無關事物），累計達上限次數（3 次 Strikes）即宣告失敗。
     - 超過最大允許對話輪次仍未達成目標時同樣宣告失敗，並給予針對性的改進指引與重試按鈕。

4. **端側流暢運作的必要組件架構**
   - **16kHz 16-bit Mono PCM 音訊管線**：原生直通 LFM3-Audio 特徵處理。
   - **端側 Smart Scenario Simulator 備援**：支援完全無外部 GPU 伺服器的「本機即開即用語音體驗」，即使在飛航模式或無網路環境下亦可完整演練全部對話與評判流程。
   - **動態音訊波形視覺化 (Dynamic Waveform Visualizer)**：隨著用戶發音與 AI 回覆節奏平滑起伏。
   - **雙語切換**：以英文對話為主，可一鍵切換日語情境。

---

## 🏗️ 系統架構設計

```
com.tutorapp.fluenttutor
├── audio/
│   ├── AudioRecorder.kt          // 16kHz PCM 串流錄音與即時切片
│   ├── VoiceActivityDetector.kt  // 端側 RMS 自適應底噪 VAD 自動斷句
│   ├── AudioPlayer.kt            // PCM/WAV AudioTrack 播放與系統 TTS 雙引擎
│   └── WaveformBar.kt            // 動態波形跳動視覺化元件
├── engine/
│   ├── LfmAudioClient.kt         // LFM3-Audio (2B) 服務接口 (WebSocket / REST)
│   ├── MissionJudgeEngine.kt     // 目標達成、流暢度評估與偏離判定小模型
│   └── SmartScenarioSimulator.kt // 端側多輪情境智慧模擬推論引擎
├── model/
│   ├── Scenario.kt               // 場景關卡定義 (英文/日文)
│   ├── MissionGoal.kt            // 任務目標與過關狀態機
│   ├── ChatMessage.kt            // 對話訊息實體
│   └── EvaluationResult.kt       // 回合評估與最終結算成績單
├── ui/
│   ├── components/               // 任務進度卡、氣泡、結算彈窗、控制面板
│   ├── screens/                  // 場景選擇、主對話介面、模型設定
│   ├── theme/                    // Material 3 深色科技主題
│   └── viewmodel/                // TutorViewModel 核心狀態機
└── MainActivity.kt               // 主入口與動態錄音權限管理
```

---

## 🚀 快速開始

### 需求環境
- Android Studio Ladybug / Koala 或更新版本
- JDK 17 或 JDK 21 (推薦 Android Studio 自帶的 JBR)
- Android SDK 35 (最低支援 Android 8.0 / API 26)

### 本地編譯與運行
```bash
# 執行單元測試
./gradlew test

# 編譯 Debug APK
./gradlew assembleDebug
```

### 連接 LFM3-Audio 伺服器
若您擁有部署好 Liquid AI **LFM3-Audio-2B**（或相容音訊端點）的伺服器：
1. 點擊右上角「⚙️ 設定」按鈕。
2. 關閉「啟用端側智慧模擬 (Mock Mode)」。
3. 輸入您的端點 URL（例如：`http://192.168.1.100:8000/v1/audio/chat`）及可選的 API Key。
4. 點擊「儲存設定」即可開始與大型語音模型即時對答！

---

## 🧪 測試驗證

本專案包含完整的單元測試集：
- `VoiceActivityDetectorTest`：驗證 RMS 計算精度與靜音時長斷句切片有效性。
- `MissionJudgeEngineTest`：驗證子目標命中、偏離主題檢測（Strikes）、全部目標達成宣布過關與超次失敗邏輯。
