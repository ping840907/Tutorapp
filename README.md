# FluentTutor AI 🎙️ - SM8850 旗艦級端側語音對答情境任務學習 App

基於 **高通驍龍 8 Elite (Snapdragon 8 Elite / SM8850)** 旗艦晶片算力與記憶體頻寬，採用 **端側雙引擎協同架構 (Dual-Engine Edge Architecture)**：
- **主推論引擎 (Primary Engine)**：Speech-to-Speech (S2S) 語音流對話生成。
- **副判定引擎 (Judge & Anti-Hallucination Secondary Engine)**：專責防範 2B 多模態模型幻覺，提供精確的子目標達成狀態機、順序依賴性約束與語意偏離（Strike）審查。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2035-brightgreen.svg)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-blue.svg)](https://developer.android.com/jetpack/compose)
[![Chipset](https://img.shields.io/badge/Target%20Chip-Snapdragon%20SM8850-red.svg)](https://qualcomm.com)

---

## 🌟 核心架構與改進成果

### 1. 雙引擎協同抗幻覺機制 (Dual-Engine Anti-Hallucination)
- **核心痛點**：2B Speech-to-Speech 模型擅長自然語音交互，但在執行結構化目標判定時容易產生幻覺 (Hallucination) 或出現寬鬆包含誤判。
- **解決方案**：
  - **主引擎 (`LfmSpeechToSpeechEngine`)**：專注 16kHz PCM 音訊分析、多輪上下文狀態追蹤與端側語音波形合成。
  - **副引擎 (`MissionJudgeEngine`)**：完全獨立運行，採用全詞邊界（Regex Word Boundary）與負向否定詞過濾（例如「no oat milk」不會被誤判為完成點餐），並強制遵守階段順序約束（例如點餐場景必須依序：指定飲品 ➔ 冷熱尺寸 ➔ 結帳付款，防止一句話作弊一次過關）。

### 2. 極限榨取 SM8850 旗艦晶片算力與記憶體
- **Oryon CPU + LPDDR5X (12GB~16GB) 高頻寬優化**：
  - 零拷貝環形緩存 (Zero-Copy Ring Buffer) 與 80-bin Mel Spectrogram 特徵並行計算。
  - 即時顯示真實系統指標：即時進程 PSS RAM (MB) 與端側原生延遲 (ms)，徹底清除任何假資料。

### 3. 硬體級防回音 (AEC) 與半雙工保護
- **解決 AI 聽到自己講話並陷入自我觸發的致命迴圈**：
  - 音訊錄音來源改用 `MediaRecorder.AudioSource.VOICE_COMMUNICATION`，自動啟用高通晶片硬體級 `AcousticEchoCanceler` (AEC) 與 `NoiseSuppressor`。
  - 增加半雙工閘控：當 AI 在播放語音時，錄音管線自動靜音（Mute），播放結束後恢復，阻斷麥克風自激。

### 4. 解決雜音爆音與音訊播放取樣率混淆
- 打造專屬 `AudioPlayer`，具備 **智慧 WAV 標頭解析器**（動態識別 16kHz / 22.05kHz / 24kHz / 48kHz 取樣率與聲道），跳過 44-byte RIFF 標頭，杜絕將標頭當作 PCM 播放造成的爆音。

### 5. 單線程序列化佇列 (Sequential Channel Pipeline)
- 透過 Kotlin `Channel` 確保每一段語音依序排隊處理，杜絕多段語音並發造成的變數覆蓋與目標狀態競爭條件 (Race Condition)。

### 6. 專注深度打磨英語對話任務 (English Focused)
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
│   ├── LfmSpeechToSpeechEngine.kt # 主引擎：SM8850 S2S 語音流對話與波形合成
│   └── MissionJudgeEngine.kt      # 副引擎：防幻覺目標達成狀態機與偏離懲罰審查
├── model/
│   ├── Scenario.kt               # 精選英語任務場景
│   ├── MissionGoal.kt            # 順序約束與否定排除之目標模型
│   ├── ChatMessage.kt            # 對話實體
│   └── EvaluationResult.kt       # 成績單與星級評分
└── ui/
    ├── screens/
    │   ├── ScenarioSelectScreen.kt # 關卡任務選擇
    │   └── ConversationScreen.kt   # 雙引擎即時語音對答主畫面
    ├── components/                 # 聲波視覺化、目標進度卡、結算彈窗
    ├── viewmodel/TutorViewModel.kt # 單線程序列化協程處理狀態機
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
