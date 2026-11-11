package com.smartjourney.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 차량 화면(160dpi, 팔 길이 거리) 기준이라 글자와 버튼을 크게 잡는다.
object Palette {
    val Background = Color(0xFFF3F5F8)
    val Surface = Color.White
    val Ink = Color(0xFF111827)
    val Muted = Color(0xFF6B7280)
    val Line = Color(0xFFE5E7EB)
    val Primary = Color(0xFF1B64F2)
    val Blue = Color(0xFF1D4ED8)
    val Green = Color(0xFF15803D)
    val Orange = Color(0xFFC2410C)
    val Red = Color(0xFFB91C1C)
    val Gray = Color(0xFF4B5563)
    val DemoBackground = Color(0xFFFFF7ED)
    val DemoInk = Color(0xFF9A3412)
}

object Type {
    val Display = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    val Headline = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    val Title = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    val Body = TextStyle(fontSize = 24.sp, color = Palette.Ink)
    val Label = TextStyle(fontSize = 20.sp, color = Palette.Muted)
}

@Composable
fun SmartJourneyTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(
        primary = Palette.Primary, onPrimary = Color.White,
        background = Palette.Background, onBackground = Palette.Ink,
        surface = Palette.Surface, onSurface = Palette.Ink,
    ),
    content = content,
)

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = Palette.Surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = color) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(20.dp)
    val label = @Composable { Text(text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    if (primary) {
        Button(onClick, modifier.heightIn(min = 88.dp), enabled = enabled, shape = shape) { label() }
    } else {
        OutlinedButton(
            onClick, modifier.heightIn(min = 88.dp), enabled = enabled, shape = shape,
            border = BorderStroke(2.dp, Palette.Line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Ink),
        ) { label() }
    }
}

/** 데모 패널용 작은 버튼 */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, Palette.DemoInk.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.DemoInk),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** 색만으로 구분하지 않도록 항상 글자를 같이 쓴다. */
@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** 큰 숫자 + 단위 + 라벨 (C-08) */
@Composable
fun TimeBlock(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(label, style = Type.Label)
        Text(
            buildString { append(value); append(unit) },
            style = Type.Display,
            maxLines = 1,
        )
    }
}
