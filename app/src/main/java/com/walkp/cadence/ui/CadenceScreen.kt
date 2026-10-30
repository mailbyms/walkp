package com.walkp.cadence.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkp.cadence.CadenceEngine
import com.walkp.cadence.Placement
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CadenceScreen(
    snapshot: CadenceEngine.Snapshot,
    sensorAvailable: Boolean,
    placement: Placement,
    recording: Boolean,
    recordingStartMs: Long?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPlacementChange: (Placement) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 避开状态栏和打孔摄像头区域（挖孔屏上徽章会被摄像头挡住）
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StateBadge(snapshot.state)

            Spacer(Modifier.height(12.dp))

            PlacementSelector(
                placement = placement,
                onPlacementChange = onPlacementChange
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "当前步频",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 大字号：手持时用户常常在外摆着手臂看，1.5 米内要能读清
            Text(
                text = snapshot.instantSpm?.roundToInt()?.toString() ?: "--",
                fontSize = 104.sp,
                fontWeight = FontWeight.Bold,
                color = when (snapshot.state) {
                    CadenceEngine.WalkState.WALKING -> MaterialTheme.colorScheme.primary
                    CadenceEngine.WalkState.UNSTABLE -> MaterialTheme.colorScheme.outline
                    CadenceEngine.WalkState.IDLE -> MaterialTheme.colorScheme.outline
                },
                lineHeight = 104.sp
            )

            Text(
                text = "步 / 分钟",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "最近 1 分钟平均",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = snapshot.avg60Spm?.roundToInt()?.toString() ?: "--",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "置信度",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(snapshot.confidence * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "频谱 / 峰值",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "${snapshot.spectralSpm?.roundToInt() ?: 0} / ${snapshot.peakSpm?.roundToInt() ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "最近 60 秒",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            CadenceChart(
                history = snapshot.history,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )

            Spacer(Modifier.height(12.dp))

            RecordControls(
                recording = recording,
                recordingStartMs = recordingStartMs,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = when {
                    !sensorAvailable -> "此设备没有加速度计，无法测量。"
                    snapshot.state == CadenceEngine.WalkState.IDLE && placement == Placement.HAND ->
                        "手持手机自然摆臂走动。手臂僵住（比如盯着屏幕不动）会检测不到步频。"
                    snapshot.state == CadenceEngine.WalkState.IDLE ->
                        "把手机放进口袋后开始走动，读数会在几秒内出现。"
                    snapshot.state == CadenceEngine.WalkState.UNSTABLE -> "信号短暂中断，保持正常节奏。"
                    else -> "记录期间可锁屏放入口袋，通知栏会持续显示当前步频。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
        }

        // 主题切换：跟随系统 / 浅色 / 深色 循环，浮在右上角
        ThemeModeChip(
            mode = themeMode,
            onClick = { onThemeModeChange(themeMode.next()) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(12.dp)
        )

        // 历史入口，左上角
        TextButton(
            onClick = onOpenHistory,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(4.dp)
        ) {
            Text("历史", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacementSelector(
    placement: Placement,
    onPlacementChange: (Placement) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        Placement.entries.forEachIndexed { index, p ->
            SegmentedButton(
                selected = placement == p,
                onClick = { onPlacementChange(p) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = Placement.entries.size
                )
            ) {
                Text(p.label)
            }
        }
    }
}

@Composable
private fun RecordControls(
    recording: Boolean,
    recordingStartMs: Long?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    // 记录时长每秒刷新一次
    val elapsedSec by produceState(0L, recordingStartMs) {
        val start = recordingStartMs
        if (start == null) {
            value = 0L
            return@produceState
        }
        while (true) {
            value = (System.currentTimeMillis() - start) / 1000
            delay(1_000)
        }
    }

    Button(
        onClick = if (recording) onStopRecording else onStartRecording,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = if (recording) ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ) else ButtonDefaults.buttonColors()
    ) {
        Text(
            text = if (recording) "停止记录" else "开始记录",
            style = MaterialTheme.typography.titleMedium
        )
    }

    if (recording) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "已记录 ${formatDuration(elapsedSec)} · 可锁屏或放入口袋",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

private fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun ThemeModeChip(mode: ThemeMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun StateBadge(state: CadenceEngine.WalkState) {
    val (label, bg, fg) = when (state) {
        CadenceEngine.WalkState.WALKING -> Triple(
            "步行中",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        CadenceEngine.WalkState.UNSTABLE -> Triple(
            "信号不稳",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        CadenceEngine.WalkState.IDLE -> Triple(
            "未检测到步行",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(color = fg, radius = size.minDimension / 2f)
            }
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}
