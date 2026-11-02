package com.tutorapp.fluenttutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.ui.theme.DarkBackground
import com.tutorapp.fluenttutor.ui.theme.PrimaryBlue
import com.tutorapp.fluenttutor.ui.theme.SecondaryTeal
import com.tutorapp.fluenttutor.ui.theme.SurfaceDark
import com.tutorapp.fluenttutor.ui.theme.SurfaceVariantDark
import com.tutorapp.fluenttutor.ui.theme.TextMuted
import com.tutorapp.fluenttutor.ui.theme.TextPrimary
import com.tutorapp.fluenttutor.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentServerUrl: String,
    isMockMode: Boolean,
    onSaveConfig: (url: String, apiKey: String, mockMode: Boolean) -> Unit,
    onBack: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(currentServerUrl) }
    var apiKey by remember { mutableStateOf("") }
    var mockEnabled by remember { mutableStateOf(isMockMode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系統設定與模型配置", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LFM3-Audio 模型伺服器卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🤖 FLM3 / LFM3 Audio 模型設定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "支援 Liquid AI LFM3-Audio-2B 多模態端側/邊緣語音模型或自建推論伺服器。",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "啟用端側智慧模擬 (Mock Mode)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = "無需連接外網 GPU 伺服器即可直接流暢體驗", fontSize = 11.sp, color = TextMuted)
                        }
                        Switch(
                            checked = mockEnabled,
                            onCheckedChange = { mockEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlue)
                        )
                    }

                    if (!mockEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("LFM3 Audio Server Endpoint URL") },
                            placeholder = { Text("http://192.168.1.100:8000/v1/audio/chat") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = SurfaceVariantDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key (Optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = SurfaceVariantDark,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    }
                }
            }

            // 行動端流暢運作架構說明
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚡ 行動端極速即時對答技術架構",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryTeal
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "1. 【端側自適應 VAD (Voice Activity Detector)】\n" +
                               "   以 RMS 能量檢測結合自適應環境底噪門檻，在用戶說話結束後的 600ms~700ms 內瞬間自動斷句並觸發分段傳輸，避免傳統整段錄音的上傳延遲。\n\n" +
                               "2. 【分段流式管線 (Segmented Audio Pipeline)】\n" +
                               "   採用 16kHz 16-bit Mono PCM 格式，直接對齊 LFM3-Audio 的音訊特徵維度。\n\n" +
                               "3. 【端側過關/偏離判定小模型 (Mission Judge Engine)】\n" +
                               "   具備語意目標匹配、語速流暢度評估與偏離情境懲罰（Off-Topic Strikes），達成目標即時宣布過關，偏離主題達上限則宣告失敗。",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // 儲存按鈕
            Button(
                onClick = {
                    onSaveConfig(serverUrl, apiKey, mockEnabled)
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = "Save", tint = Color.White)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "儲存設定", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
