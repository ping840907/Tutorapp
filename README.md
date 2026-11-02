# FluentTutor AI 🎙️ - SM8850 旗艦級端側語音對答情境任務學習 App

基於 **高通驍龍 8 Elite (Snapdragon 8 Elite / SM8850)** 旗艦晶片，採用 **單一模型端側架構 (Single-Model Edge Architecture)**：
- **唯一模型 LFM3-Audio**：同一次推論同時輸出口說回覆與結構化判定 (逐字稿、是否離題、達成的目標 id、相關度/流暢度評分、一句回饋)。
- **任務狀態機 (MissionStateMachine)**：不做任何語意判斷，只驗證模型輸出 (忽略未知 id、強制目標順序、離題 Strike 計數、分數夾在 0~100) 並記錄、推進過關/失敗。
- **推論後端 (LfmBackend)**：真實 LFM3-Audio 執行期實作此介面即可接入；目前內建 StandInLfmBackend 為**開發替身** (只理解手動輸入文字，純語音會回報聽不清楚)，**尚未接上真實模型**。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-brightgreen.svg)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-blue.svg)](https://developer.android.com/jetpack/compose)
[![Chipset](https://img.shields.io/badge/Target%20Chip-Snapdragon%20SM8850-red.svg)](https://qualcomm.com)
[![Retrospective](https://img.shields.io/badge/Docs-踩坑紀錄與復盤總結-orange.svg)](PITFALLS_AND_RETROSPECTIVE.md)

---

> 📖 **開發歷程與避坑指南**：請參閱 [踩坑紀錄與失敗嘗試總結 (PITFALLS_AND_RETROSPECTIVE.md)](PITFALLS_AND_RETROSPECTIVE.md)，包含語音輸入收音 HAL 靜音、VAD 門檻、神經 Vocoder 沙沙白噪音消除、手動輸入與死板模板解耦之詳細復盤。

---

## 🌟 核心架構與改進成果

### 1. 單一模型 + 狀態機 (Single Model, State-Machine Recording)
- **輸出協議**：模型在口說回覆之後附上 `<verdict>{json}</verdict>` (見 `ModelOutputParser`)，提示詞由 `LfmPromptBuilder` 依場景與目標進度產生。
- **防幻覺護欄 (不靠第二個模型)**：未知 goal id 忽略、離題回合不記錄目標、前置目標未完成不接受後續目標、輸出缺 verdict 或 transcript 為空時該回合不計分。
- **Session 隔離**：重設/返回場景會取消進行中的處理並丟棄排隊語音；單一片段例外不會讓處理協程死亡；處理中不堆積新語音。

### 2. 極限榨取 SM8850 旗艦晶片算力與記憶體
- **Oryon CPU + LPDDR5X (12GB~16GB) 高頻寬優化**：
  - 300ms 前導環形緩存 + 自適應 VAD 分段收音。
  - 即時顯示真實系統指標：即時進程 PSS RAM (MB) 與端側原生延遲 (ms)，徹底清除任何假資料。

### 3. 硬體級防回音 (AEC) 與半雙工保護
- **解決 AI 聽到自己講話並陷入自我觸發的致命迴圈**：
  - 音訊錄音來源改用 `MediaRecorder.AudioSource.VOICE_COMMUNICATION`，自動啟用高通晶片硬體級 `AcousticEchoCanceler` (AEC) 與 `NoiseSuppressor`。
  - 增加半雙工閘控：當 AI 在播放語音時，錄音管線自動靜音（Mute），播放結束後恢復，阻斷麥克風自激。

### 4. 即時動態 NPC 動畫頭像系統 (Animated NPC Avatar & Real-time Lip-Sync)
- **專屬角色造型 (`NpcAvatarStyle`)**：
  - ☕ **Sarah (`BARISTA_SARAH`)**：溫暖翡翠綠圍裙咖啡師（丸子頭、微笑酒窩）。
  - 🏨 **David (`CONCIERGE_DAVID`)**：Grand Hyatt 皇家藍西裝禮賓經理（金色領帶與徽章）。
  - ✈️ **Elena (`AGENT_ELENA`)**：甘迺迪機場深藍制服地勤專員（桃紅絲巾與耳機麥克風）。
- **5 種互動狀態機 (`NpcInteractionState`)**：
  - `IDLE`（自然呼吸起伏與隨機單/雙次眨眼）、`LISTENING_IDLE`（微微前傾專注傾聽）、`LISTENING_ACTIVE`（隨用戶音量點頭與雙層光圈脈衝）、`THINKING`（視線朝右上凝視、歪頭思考與動態思考氣泡）、`SPEAKING`（由 `AudioPlayer` 播放 PCM RMS 即時振幅驅動的精準嘴型開合 Lip-Sync 與音節律動）。
- **5 種情緒表情回饋 (`NpcEmotion`)**：
  - `WELCOMING`（友善迎賓微笑）、`HAPPY_ENCOURAGING`（回答切題時的鼓勵笑容）、`EXCITED_PROUD`（達成子目標或過關時的彎月笑眼 `^ ^`、紅潤雙頰與閃爍星芒）、`CONFUSED_WARNING`（偏離主題 Strike 時的高低挑眉與問號警示）、`DISAPPOINTED_FAILED`（任務失敗時的八字垂眉與失落表情）。

### 5. 解決雜音爆音、RIFF Chunk 動態解析與語音播放同步
- 打造專屬 `AudioPlayer`，具備 **動態 RIFF Chunk 標頭掃描器**（精準定位 `"fmt "` 與 `"data"` 區段，避免 Android 合成 WAV 含額外 `LIST` chunk 時因固定 44-byte 偏移造成的爆音），並透過 `MODE_STREAM` 分塊寫入即時計算播放振幅。

### 6. 單線程序列化佇列 (Sequential Channel Pipeline)
- 透過 Kotlin `Channel` 確保每一段語音依序排隊處理，杜絕多段語音並發造成的變數覆蓋與目標狀態競爭條件 (Race Condition)。

### 7. 專注深度打磨英語對話任務 (English Focused)
- 移除日語過渡程式碼與多餘的 server/ 後端腳本，架構純粹簡潔：
  - **Scenario 1: Coffee Shop Order**（紐約 Bean & Leaf 咖啡廳點燕麥奶拿鐵）
  - **Scenario 2: Hotel Front Desk**（Grand Hyatt 櫃檯辦理入住手續與詢問設施）
  - **Scenario 3: Airport Boarding Gate**（甘迺迪國際機場登機手續與座位選擇）

---

## 🏗️ 目錄架構

```
com.tutorapp.fluenttutor
├── audio/
│   ├── AudioRecorder.kt          # SM8850 硬體 AEC、300ms 前導緩衝、半雙工保護錄音器
│   ├── VoiceActivityDetector.kt  # 取樣數精準計時、自適應噪聲基線即時斷句 VAD
│   └── AudioPlayer.kt            # 智慧 WAV 標頭解析、多取樣率 AudioTrack 播放器
├── engine/
│   ├── LfmSpeechToSpeechEngine.kt # 唯一推論引擎：提示詞 → LfmBackend → 解析 verdict
│   ├── LfmBackend.kt / LfmPromptBuilder.kt / ModelTurnOutput.kt  # 後端介面、提示詞、輸出協議與解析器`n│   ├── StandInLfmBackend.kt       # 開發替身 (非真實模型)`n│   └── MissionStateMachine.kt     # 任務狀態機：驗證並記錄模型判定
├── model/
│   ├── Scenario.kt               # 精選英語任務場景與專屬 NPC 角色設定
│   ├── NpcAvatarModels.kt        # NPC 造型、互動狀態與情緒表情狀態機定義
│   ├── MissionGoal.kt            # 順序約束與否定排除之目標模型
│   ├── ChatMessage.kt            # 對話實體
│   └── EvaluationResult.kt       # 成績單與星級評分
└── ui/
    ├── screens/
    │   ├── ScenarioSelectScreen.kt # 關卡任務選擇
    │   └── ConversationScreen.kt   # 即時語音對答與動態 NPC 主畫面
    ├── components/
    │   ├── NpcAvatarCard.kt        # 即時動態向量 NPC 頭像 (Lip-Sync / 表情 / 眨眼)
    │   └── ...                     # 聲波視覺化、目標進度卡、結算彈窗
    ├── viewmodel/TutorViewModel.kt # 單線程序列化協程處理與 NPC 情緒狀態機
    └── MainActivity.kt             # 生命週期保護 (退後台停收音) 與動態權限
```

---

## 🧪 驗證與建置

```bash
# 執行單元測試
./gradlew test

# 編譯 Debug APK
./gradlew assembleDebug
```
產出 APK 位於：`app/build/outputs/apk/debug/app-debug.apk`。
