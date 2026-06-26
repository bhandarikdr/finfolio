package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class ChartSlice(val value: Float, val color: Color, val label: String)

/**
 * Phase 3.2: Asset Breakdown Visuals.
 * Simple high-performance Donut Chart for sector allocation.
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier.size(200.dp),
    strokeWidth: Float = 40f
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    
    Canvas(modifier = modifier) {
        var startAngle = -90f
        
        slices.forEach { slice ->
            val sweepAngle = (slice.value / total) * 360f
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweepAngle
        }
    }
}
