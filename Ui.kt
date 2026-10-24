package com.yesmin.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

val BG = Color(0xFF0B0716)
val CARD = Color(0xFF191030)
val PURPLE = Color(0xFF9B5CFF)
val TEAL = Color(0xFF2DE2C9)
val PINK = Color(0xFFFF5FA2)
val YEL = Color(0xFFFFD166)
val DIM = Color(0xFFB8A9D9)

@Composable
fun YesminTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = PURPLE, background = BG, surface = CARD,
            onSurface = Color.White, onBackground = Color.White, onPrimary = Color.White),
        content = content)
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(50))
        .background(if (selected) PURPLE else Color(0xFF2A1D4A)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

/** 3D-jaisa animated orb: rang halat ke hisaab se, size awaaz ke saath. */
@Composable
fun Orb(dp: Dp) {
    val st by Bus.state.collectAsState()
    val lv by Bus.level.collectAsState()
    val muted by Bus.muted.collectAsState()
    val t = rememberInfiniteTransition(label = "orb")
    val ph by t.animateFloat(0f, 6.2831855f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart), label = "ph")
    val (c1, c2) = if (muted) Color(0xFF6A6A7A) to Color(0xFF3A3A4A) else when (st) {
        VState.LISTENING -> TEAL to PURPLE
        VState.THINKING -> PURPLE to YEL
        VState.SPEAKING -> PINK to PURPLE
        VState.ERROR -> Color(0xFFFF4D4D) to Color(0xFF5A1F1F)
        VState.CONNECTING -> PURPLE to Color(0xFF3A2370)
        VState.IDLE -> Color(0xFF6B4CC0) to Color(0xFF2A1D4A)
    }
    Canvas(Modifier.size(dp)) {
        val c = center
        val r = size.minDimension / 2f * (0.78f + 0.14f * lv + 0.025f * sin(ph))
        drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 0.5f), Color.Transparent), center = c, radius = r * 1.55f), r * 1.55f, c)
        drawCircle(Brush.radialGradient(listOf(c1, c2, Color(0xFF0B0716)), center = c + Offset(-r * 0.3f, -r * 0.3f), radius = r * 1.4f), r, c)
        for (i in 0..3) {
            val a = ph + i * 1.6f
            drawCircle(c1.copy(alpha = 0.22f), r * (0.28f + 0.05f * i), c + Offset(cos(a) * r * 0.42f, sin(a * 1.3f) * r * 0.42f))
        }
        drawCircle(Color.White.copy(alpha = 0.28f), r * 0.14f, c + Offset(-r * 0.38f, -r * 0.42f))
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit, active: Boolean, onOrb: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).background(CARD), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun item(i: Int, icon: String, label: String, m: Modifier) {
            Column(m.clickable { onTab(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, fontSize = 20.sp)
                Text(label, fontSize = 11.sp, color = if (tab == i) PURPLE else DIM)
            }
        }
        item(0, "🏠", "Home", Modifier.weight(1f))
        item(1, "💬", "Chat", Modifier.weight(1f))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(
                Brush.radialGradient(if (active) listOf(TEAL, PURPLE) else listOf(PURPLE, Color(0xFF3A2370)))).clickable(onClick = onOrb),
                contentAlignment = Alignment.Center) { Text(if (active) "■" else "🎙", fontSize = 22.sp, color = Color.White) }
        }
        item(2, "📜", "History", Modifier.weight(1f))
        item(3, "⚙️", "Settings", Modifier.weight(1f))
    }
}
