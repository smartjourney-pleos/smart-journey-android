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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 차량 화면(160dpi, 팔 길이 거리) 기준이라 글자와 버튼을 크게 잡는다.
// 차량 주간/야간 모드(uiMode night)를 따라 색을 바꾼다. 대비는 4.5:1 이상 (Pleos UI Design Guide).
private val LocalDark = staticCompositionLocalOf { false }

object Palette {
    private val dark: Boolean @Composable @ReadOnlyComposable get() = LocalDark.current
    private fun pick(dark: Boolean, day: Long, night: Long) = Color(if (dark) night else day)

    val Background: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFFF3F5F8, 0xFF0E1116)
    val Surface: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFFFFFFFF, 0xFF1A1E25)
    val Ink: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFF111827, 0xFFF3F4F6)
    val Muted: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFF5F6672, 0xFFA3AAB5)
    val Line: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFFE5E7EB, 0xFF2E333B)
    val Primary: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFF1B64F2, 0xFF2563EB)
    val Blue: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFF1D4ED8, 0xFF60A5FA)
    val Green: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFF15803D, 0xFF4ADE80)
    val Orange: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFFC2410C, 0xFFFB923C)
    val Red: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFFB91C1C, 0xFFF87171)
    val Gray: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFF4B5563, 0xFF9CA3AF)
    val DemoBackground: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFFFFF7ED, 0xFF2A1A0E)
    val DemoInk: Color @Composable @ReadOnlyComposable get() = pick(dark, 0xFF9A3412, 0xFFFDBA74)
}

object Type {
    val Display: TextStyle @Composable @ReadOnlyComposable
        get() = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    val Headline: TextStyle @Composable @ReadOnlyComposable
        get() = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    val Title: TextStyle @Composable @ReadOnlyComposable
        get() = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    val Body: TextStyle @Composable @ReadOnlyComposable get() = TextStyle(fontSize = 24.sp, color = Palette.Ink)
    val Label: TextStyle @Composable @ReadOnlyComposable get() = TextStyle(fontSize = 20.sp, color = Palette.Muted)
}

@Composable
fun SmartJourneyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalDark provides dark) {
        val colors = if (dark) darkColorScheme(
            primary = Palette.Primary, onPrimary = Color.White,
            background = Palette.Background, onBackground = Palette.Ink,
            surface = Palette.Surface, onSurface = Palette.Ink,
        ) else lightColorScheme(
            primary = Palette.Primary, onPrimary = Color.White,
            background = Palette.Background, onBackground = Palette.Ink,
            surface = Palette.Surface, onSurface = Palette.Ink,
        )
        MaterialTheme(colorScheme = colors, content = content)
    }
}

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
