package com.walkp.cadence.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap

/**
 * 步频折线图（通用组件）：null 表示该段无有效数据，折线在此处断开。
 * 主屏"最近 60 秒"和会话详情"每分钟趋势"共用。
 * 颜色走 MaterialTheme 槽位，深浅色主题自动适配。
 */
@Composable
fun CadenceChart(
    history: List<Float?>,
    modifier: Modifier = Modifier,
    minSpm: Float = 60f,
    maxSpm: Float = 180f
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun yOf(v: Float): Float = h - (v - minSpm) / (maxSpm - minSpm) * h

        // 参考线：90 / 120 / 150 spm
        for (g in listOf(90f, 120f, 150f)) {
            val y = yOf(g)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        val n = history.size
        if (n < 2) return@Canvas
        val step = w / (n - 1)
        var prevY: Float? = null
        for (i in 0 until n) {
            val v = history[i]
            if (v == null) {
                prevY = null
                continue
            }
            val x = i * step
            val y = yOf(v.coerceIn(minSpm, maxSpm))
            val p = prevY
            if (p != null) {
                drawLine(
                    color = lineColor,
                    start = Offset((i - 1) * step, p),
                    end = Offset(x, y),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
            drawCircle(color = dotColor, radius = 2.5f, center = Offset(x, y))
            prevY = y
        }
    }
}
