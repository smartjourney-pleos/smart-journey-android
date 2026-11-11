package com.smartjourney.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartjourney.vehicle.R

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = Palette.Surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(PleosSpace.CardRadius), color = color) {
        Column(Modifier.padding(PleosSpace.Section), verticalArrangement = Arrangement.spacedBy(PleosSpace.Gap), content = content)
    }
}

/** ivi_basic_button large: 100dp, radius 32dp, label 32/40sp (67:22525). */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interactive = enabled && !loading
    Button(
        onClick,
        modifier.heightIn(min = 100.dp).semantics {
            if (loading) stateDescription = "처리 중"
        },
        enabled = interactive,
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Palette.Primary else Palette.Button,
            contentColor = if (primary) Palette.OnPrimary else Palette.Ink,
            disabledContainerColor = Palette.Button,
            disabledContentColor = if (loading) Palette.Ink else Palette.Ink.copy(alpha = 0.32f),
        ),
        border = if (interactive) null else BorderStroke(2.dp, Palette.Line),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(32.dp), color = Palette.Ink, strokeWidth = 3.dp)
            }
            Text(text, style = Type.Title.copy(color = Color.Unspecified), textAlign = TextAlign.Center)
        }
    }
}

/** ivi_basic_button small: 64dp, radius 16dp, label 26/34sp. */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick, modifier.heightIn(min = 64.dp), enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Button, contentColor = Palette.Ink,
            disabledContainerColor = Palette.Button,
            disabledContentColor = Palette.Ink.copy(alpha = 0.32f),
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontFamily = Type.Body.fontFamily, fontSize = 26.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.background(color.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(text, style = Type.Caption.copy(color = color, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** ivi_stepper: the original Figma minus/plus assets, not text substitutes (67:25154). */
@Composable
fun PartyStepper(party: Int?, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.widthIn(max = 420.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = Palette.Elevated, border = BorderStroke(2.dp, Palette.Line), shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.heightIn(min = 100.dp).padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            IconButton({ onChange(((party ?: 2) - 1).coerceAtLeast(1)) }, Modifier.size(64.dp), enabled = party == null || party > 1) {
                Icon(painterResource(R.drawable.pleos_minus), "탑승 인원 줄이기", Modifier.size(44.dp))
            }
            VerticalDivider(Modifier.height(64.dp), thickness = 2.dp, color = Palette.Line)
            Text(party?.let { "${it}명" } ?: "–", Modifier.weight(1f), style = Type.Title.copy(fontSize = 36.sp, lineHeight = 44.sp), textAlign = TextAlign.Center)
            VerticalDivider(Modifier.height(64.dp), thickness = 2.dp, color = Palette.Line)
            IconButton({ onChange(((party ?: 0) + 1).coerceAtMost(9)) }, Modifier.size(64.dp), enabled = party == null || party < 9) {
                Icon(painterResource(R.drawable.pleos_plus), "탑승 인원 늘리기", Modifier.size(44.dp))
            }
        }
    }
}

@Composable
fun TimeBlock(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 260.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = Type.Label)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, style = if (compact) Type.Headline else Type.Display, maxLines = 1)
                Text(unit, Modifier.padding(bottom = if (compact) 10.dp else 14.dp), style = Type.Body, maxLines = 1)
            }
        }
    }
}
