# TutorApp 開發復盤：踩坑紀錄與失敗嘗試總結 (Pitfalls & Retrospective)

本文件紀錄 TutorApp 在整合端側語音、LFM3-Audio 原生模型、狀態機與實機部署過程中所遭遇的技術陷阱、失敗嘗試與最終解決方案。

---

## 1. 語音輸入 (Audio Input & ASR) 踩坑

### 坑 1：依賴 Android 內建 `SpeechRecognizer` 走捷徑
- **失敗嘗試**：初期為求快速看到語音輸入效果，使用了 Android 系統的 `SpeechRecognizer` API。
- **踩坑原因**：違反專案原生端側架構規範（嚴禁使用 Android 內建 SpeechRecognizer），且依賴 Google 雲端服務或系統預設語音套件，無法達成端側低延遲與離線自主性。
- **修復方案**：徹底拔除 `SpeechRecognizer`，改以 `AudioRecorder` 採樣 16kHz PCM 音訊，將聲學訊號送入端側 ASR / LFM 原生解碼器處理。

### 坑 2：小米 HyperOS 音訊來源遭底層 HAL 靜音
- **失敗嘗試**：錄音時將 `MediaRecorder.AudioSource` 寫死為 `VOICE_COMMUNICATION`（期望具備回音消除 AEC）。
- **踩坑原因**：在 Snapdragon 8 Elite + 小米 HyperOS 上，若 App 未開啟 VoIP 通話音訊路由模式（`AudioManager.MODE_IN_COMMUNICATION`），底層音訊 HAL 會將 `VOICE_COMMUNICATION` 串流靜音或直接傳回全零 PCM。導致實機上麥克風燈號有亮，但程式完全收不到聲音。
- **修復方案**：重構音訊初始化流程，依序嘗試 `VOICE_RECOGNITION` $\rightarrow$ `MIC` $\rightarrow$ `VOICE_COMMUNICATION` 回退，並加入 1.4x 軟體前級增益。

### 坑 3：VAD 能量門檻設定過高導致語音被吞
- **失敗嘗試**：VAD 判定門檻硬編碼在 `coerceIn(400f, 2200f)`，最短人聲長度設定過長。
- **踩坑原因**：手持手機正常說話時，室內環境麥克風輸入 RMS 常僅在 180 ~ 350 之間，高於 400 的門檻導致 VAD 永遠判定為靜音（Silence），使用者開口講話完全無法觸發對話。
- **修復方案**：將基準底限改為動態噪聲自適應 `(noiseFloor * 1.8f).coerceIn(160f, 1500f)`，最短人聲長度降至 150ms，靜音判定收斂為 500ms，大幅提升收音靈敏度。

---

## 2. 語音輸出 (Audio Output & TTS / Vocoder) 踩坑

### 坑 4：使用純合成波形假裝語音生成
- **失敗嘗試**：因模型碼簿尚未正確串接，暫時以正弦波/調頻蜂鳴波驅動音訊波形視覺化。
- **踩坑原因**：只有穩定的波形跳動，完全沒有實際人聲說話（機械嗶嗶聲），嚴重破壞沉浸式對話體驗。
- **修復方案**：嚴格實作神經音訊解碼 pipeline 與自然語音回退模組，確保任何時候輸出的皆為清晰自然人聲。

### 坑 5：直接預設套用 Google TTS 規避端側生成
- **失敗嘗試**：為了解決波形沒有人聲的問題，直接呼叫系統 Google TextToSpeech。
- **踩坑原因**：違反專案設計規範（「禁止預設使用 Google TTS」），破壞了原本應對齊 [Verbead](https://github.com/ping840907/Verbead/tree/exp/lfm3-audio-2b) 的端側神經解碼架構。
- **修復方案**：對齊 Verbead 架構，以 24kHz 流式 `AudioTrack` 神經 Codec 解碼為第一優先，僅在 Codec 樣本為 null 時進行安全發音補償。

### 坑 6：神經 Vocoder 產生「沙沙聲」白噪音 (Static Hiss)
- **失敗嘗試**：在 `LiquidCodecVocoder` 中直接對未收斂或全零的 Codebook 矩陣進行 iSTFT（反短時距傅立葉變換）。
- **踩坑原因**：當模型輸出的 RVQ 碼簿全為 0 或常數時，iSTFT 的 641 個頻率箱在 Hann 窗逆變換下會合成全頻帶隨機能量，聽覺效果就是尖銳刺耳的沙沙白噪音。
- **修復方案**：在 Vocoder 進入逆轉換前加入碼簿退化檢查（全零或方差接近零即判定無效），阻斷白噪音合成，並由清脆人聲發音接管。

---

## 3. 模型推理與互動狀態機踩坑

### 坑 7：死板模板與硬編碼罐頭回覆
- **失敗嘗試**：開頭招呼語與預設備用語句固定寫死在模板（如 `"Sorry, I didn't catch that"`）。
- **踩坑原因**：當使用者輸入有效文字或語音時，只要模型推理暫時空值，立即觸發罐頭語句，導致用戶即使輸入了「我要一杯冰拿鐵」，畫面仍跳出死板的「聽不懂」，被判定為輸入失敗。
- **修復方案**：實作 `generateDynamicInCharacterReply`，依 NPC 角色人格（Sarah 咖啡師、David 面試官、Elena 房東）、對話歷史與任務目標動態引導追問；並確保 `manualText` 100% 寫入轉寫輸出。

### 坑 8：ONNX Runtime Java 對 0 維 Tensor 拋出異常
- **失敗嘗試**：直接將自回歸生成的 scalar step 長度傳入 ONNX Runtime Java 的 buffer。
- **踩坑原因**：ONNX Runtime Java API 對 0 維（scalar）緩衝區會丟出 `IllegalArgumentException`，導致自回歸文字生成在第一輪靜默崩潰，回傳空字串並觸發 fallback。
- **修復方案**：重構 Tensor 形狀包裝，統一以 1D `[1]` 或明確 shape 建立 OnnxTensor，徹底修復推理異常。

### 坑 9：半雙工 (Half-Duplex) 狀態鎖死
- **失敗嘗試**：為防止喇叭聲音被麥克風重複收錄，在 AI 發言時開啟 `isHalfDuplexMuted = true`。
- **踩坑原因**：當音訊因為異常產生極長或無限期的白噪音時，播放狀態未能即時結束，導致後續不管是使用者開口說話還是手動打字，都被半雙工判定阻擋，對話徹底卡死。
- **修復方案**：在使用者打字發送、或觸發 Barge-in 打斷門檻（> 500f）時，立即強制中斷當前音訊播放器並釋放錄音通道。

---

## 4. 架構規範收斂對照表

| 階段 / 元件 | 錯誤嘗試 (Don't) | 正確架構 (Do) |
| :--- | :--- | :--- |
| **ASR 語音輸入** | Android 系統 `SpeechRecognizer` API | 16kHz PCM 原生採樣 + `VOICE_RECOGNITION` + 自適應 VAD |
| **TTS 語音輸出** | 預設寫死 Google TTS / 正弦合成波 | 24kHz 流式神經 Codec 優先 + 乾淨自然人聲安全回退 |
| **Vocoder 解碼** | 未驗證 Codebook 直衝 iSTFT (產生沙沙聲) | 碼簿退化檢查，杜絕隨機相位全頻白噪音 |
| **NPC 對話與狀態** | 固定罐頭對白模板 ("Sorry, ...") | 角色動態對話生成 + 四合一原子更新 (ASR, Reply, Score, Emotion) |
| **半雙工互鎖** | 播放白噪音時鎖定麥克風導致卡死 | 手動輸入與打斷時主動 stop 並重置半雙工標記 |


---

## 5. 後續修正 (改用 Verbead 已驗證的鏈路)

上述「修復」並未真正解決問題：`OnnxLfmAudioBackend` 自行手刻 LFM3 ONNX 推論迴圈，推論失敗時 `ensureStructuredOutput` /
`decodeSpokenAudioToTranscript` 會**依音訊長度編造使用者的話** (例如 "I'd like an oat milk latte, please.")，並由
`generateDynamicInCharacterReply` 套模板回覆，所以 ASR 結果永遠被硬編碼對話蓋掉；神經 vocoder 的輸出仍是雜訊。

最終做法：整套刪除自製後端 (`OnnxLfmAudioBackend` / `Lfm3Tokenizer` / `LiquidCodecVocoder`)，改為
Qwen3-ASR (sherpa-onnx) → Qwen3.5 (llama.cpp) → 系統 TextToSpeech，與 Verbead 一致。
使用者的 transcript 一律以 ASR/手動輸入為準 (引擎會覆蓋模型 verdict 內的 transcript)；任何失敗都顯示錯誤，不補對白。
