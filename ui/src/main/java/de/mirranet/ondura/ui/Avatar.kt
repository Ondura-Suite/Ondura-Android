package de.mirranet.ondura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AvatarPalette = listOf(
    Color(0xFF4F54D4), Color(0xFF107C41), Color(0xFFC239B3), Color(0xFFCA5010),
    Color(0xFF0E7A8A), Color(0xFF6B4FBB), Color(0xFF986F0B), Color(0xFF038387),
    Color(0xFFB4009E), Color(0xFF8764B8), Color(0xFF498205), Color(0xFFD13438),
)

/** "Max Mustermann" → "MM", "info@example.org" → "IN". */
fun initials(name: String): String {
    val clean = name.substringBefore('@').replace(Regex("[^\\p{L}\\p{N} .\\-_]"), " ").trim()
    val parts = clean.split(' ', '.', '-', '_').filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}"
        parts.size == 1 -> parts[0].take(2)
        else -> "?"
    }.uppercase()
}

/** Stable color for a person (by address), like Outlook's contact circles. */
fun avatarColor(key: String): Color = AvatarPalette[Math.floorMod(key.lowercase().hashCode(), AvatarPalette.size)]

/** Parses "#rrggbb" (account colors of the core). */
fun parseHexColor(hex: String?, fallback: Color): Color = runCatching {
    val h = hex!!.removePrefix("#")
    Color(0xFF000000 or h.toLong(16))
}.getOrDefault(fallback)

@Composable
fun Avatar(name: String, key: String, modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color? = null) {
    val text = remember(name) { initials(name) }
    val bg = color ?: remember(key) { avatarColor(key) }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp,
            maxLines = 1,
        )
    }
}
