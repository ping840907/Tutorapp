package com.tutorapp.fluenttutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tutorapp.fluenttutor.model.Language
import com.tutorapp.fluenttutor.model.Scenario
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
fun ScenarioSelectScreen(
    scenarios: List<Scenario>,
    selectedLanguage: Language,
    onSelectLanguage: (Language) -> Unit,
    onSelectScenario: (Scenario) -> Unit,
    onOpenSettings: () -> Unit
) {
    val filteredScenarios = scenarios.filter { it.language == selectedLanguage }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "FluentTutor AI",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "LFM3 Audio Real-Time Voice Scenarios",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecondaryTeal
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
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
                .padding(horizontal = 16.dp)
        ) {
            // 語言切換 Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Language.values().forEach { lang ->
                    FilterChip(
                        selected = selectedLanguage == lang,
                        onClick = { onSelectLanguage(lang) },
                        label = {
                            Text(
                                text = "${lang.flag} ${lang.displayName}",
                                fontWeight = if (selectedLanguage == lang) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryBlue,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceDark,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            Text(
                text = "請選擇挑戰情境：",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 場景列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredScenarios) { scenario ->
                    ScenarioCard(
                        scenario = scenario,
                        onClick = { onSelectScenario(scenario) }
                    )
                }
            }
        }
    }
}

@Composable
fun ScenarioCard(
    scenario: Scenario,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .border(1.dp, SurfaceVariantDark, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(PrimaryBlue.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = scenario.language.flag, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = scenario.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = scenario.subtitle,
                            fontSize = 12.sp,
                            color = SecondaryTeal
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Start",
                    tint = PrimaryBlue
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = scenario.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 目標標籤
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "目標:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
                scenario.goals.forEach { goal ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceVariantDark)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = goal.title,
                            fontSize = 10.sp,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}
