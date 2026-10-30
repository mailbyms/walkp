package com.walkp.cadence.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkp.cadence.Placement
import com.walkp.cadence.data.SampleEntity
import com.walkp.cadence.data.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 历史会话列表。 */
@Composable
fun HistoryScreen(
    sessions: List<SessionEntity>,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回", color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.weight(1f))
            Text(
                text = "记录历史",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(Modifier.height(8.dp))

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有记录。\n回到主屏点「开始记录」，锁屏放口袋也能持续采集。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { s ->
                SessionRow(
                    session = s,
                    onClick = { onOpenDetail(s.id) },
                    onDelete = { onDelete(s.id) }
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    onClick: () -> Unit,
    onDelete: (Long) -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("会话及其全部每秒样本都会被删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(session.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatDateTime(session.startMs),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildString {
                        append(formatMinutes(session.durationSec))
                        append(" · ")
                        append(placementLabel(session.placement))
                        append(" · 有效 ")
                        append(formatMinutes(session.validSec))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = session.avgSpm?.roundToInt()?.toString() ?: "--",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "平均步频 · ${session.estimatedSteps} 步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 长按删除太隐蔽，放一个明确的小按钮
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = { confirmDelete = true }) {
            Text(
                "删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 会话详情：统计 + 每分钟平均趋势。 */
@Composable
fun DetailScreen(
    detail: Pair<SessionEntity, List<SampleEntity>>?,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回", color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.weight(1f))
            Text(
                text = "会话详情",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (detail == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        val (session, samples) = detail
        Spacer(Modifier.height(8.dp))

        Text(
            text = formatDateTime(session.startMs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "${placementLabel(session.placement)} · 时长 ${formatMinutes(session.durationSec)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(14.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("平均", session.avgSpm?.roundToInt()?.toString() ?: "--")
                StatItem("最低", session.minSpm?.roundToInt()?.toString() ?: "--")
                StatItem("最高", session.maxSpm?.roundToInt()?.toString() ?: "--")
                StatItem("步数", session.estimatedSteps.toString())
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "每分钟平均步频",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )

        val perMinute = rememberPerMinute(samples)
        CadenceChart(
            history = perMinute,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "共 ${samples.size} 秒 · 有效 ${session.validSec} 秒（中断部分折线断开）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun rememberPerMinute(samples: List<SampleEntity>): List<Float?> =
    remember(samples) {
        if (samples.isEmpty()) return@remember emptyList<Float?>()
        val maxSec = samples.maxOf { it.epochSec }
        val minutes = (maxSec / 60).toInt() + 1
        (0 until minutes).map { m ->
            val vs = samples.filter { it.epochSec / 60 == m.toLong() }.mapNotNull { it.spm }
            if (vs.isEmpty()) null else vs.average().toFloat()
        }
    }

private fun placementLabel(name: String): String =
    runCatching { Placement.valueOf(name).label }.getOrDefault(name)

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatMinutes(sec: Int): String =
    if (sec < 60) "${sec}秒" else "${sec / 60}分${sec % 60}秒"
